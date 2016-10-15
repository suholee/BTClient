import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.io.EOFException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Formatter;
/**
 * 
 * Handles all communication between peer and client. Maintains state information
 * 
 */
public class Peer {

	private RUBTClient client;
	private String peer_ip;
	private String peer_id;
	private int port;

	private static int HEADER_SIZE = 68;
	private static String PROTOCOL = "BitTorrent protocol";

	private boolean choked;
	private boolean peer_choked;
	private boolean connected;
	private boolean interested;
	private boolean peer_interested;
	private boolean first_sent;


	private int downloaded;
	private int uploaded;
	private int last_downloaded;
	private int last_uploaded;

	public static final int max_length = 16384;

	private byte[] bitfield;

	public Piece[] pieces = null;
	private Socket peerSocket;
	private DataInputStream fromPeer;
	private DataOutputStream	toPeer;

	private Thread producer;
	private Thread consumer;

	private Queue<Message> jobQueue;

	private boolean stopProducing;

	private boolean[] peerCompleted;

	private Object lock = new Object();

	/**
	 * 
	 * Producer thread 
	 *
	 */
	private class Producer implements Runnable {
		private RandomAccessFile f;

		public void run() {
			try {
				f = new RandomAccessFile(client.outputFile, "rw");
			} catch (IOException e) {
				System.out.println(e.getMessage());
			}
			while (true) {
				Message message;
				try {
					System.out.println("Attempting decode");
					message = Message.decode(fromPeer, peerCompleted.length);
					System.out.println("leaving decode");
				} catch (EOFException e) {
					continue;
				} catch (IOException e) {
					System.out.println("Caught IO Exception trying to decode message: " + e.getMessage());
					break;
				}
				switch (message.getID()) {
				case Message.KEEP_ALIVE_ID:
					System.out.println("Got keepalive message from peer " + getPeerId());
					break;
				case Message.CHOKE_ID:
					System.out.println("Got choke message from peer " + getPeerId());
					choked = true;
					break;
				case Message.UNCHOKE_ID:
					System.out.println("Got unchoke message from peer " + getPeerId());
					synchronized (lock) {
						choked = false;
						lock.notifyAll();
					}

					System.out.println("Notified...");

					Message.RequestMessage request = formRequest();
					System.out.println("Sending request "  + request.getIndex() + "  " + request.getOffset() + "  " + request.getBlockLength());
					jobQueue.offer(request);


					break;
				case Message.INTERESTED_ID:
					System.out.println("Got interested message from peer " + getPeerId());
					interested = true;
					if (client.getUnchoked() < client.unchoke_limit) {
						jobQueue.offer(Message.UNCHOKE);
						peer_choked = false;
						client.incrementUnchoked();
						client.want_unchoke.add(Peer.this);
					}
					break;
				case Message.UNINTERESTED_ID:
					System.out.println("Got uninterested message from peer " + getPeerId());
					interested = false;
					jobQueue.offer(Message.CHOKE);
					client.want_unchoke.remove(Peer.this);
					client.decrementUnchoked();
					break;
				case Message.HAVE_ID:
					System.out.println("Got have message from peer " + getPeerId());
					Message.HaveMessage hMessage = (Message.HaveMessage)message;
					peerCompleted[hMessage.getPieceIndex()] = true;
					client.have(hMessage.getPieceIndex());
					break;
				case Message.BITFIELD_ID:
					System.out.println("Got bitfield message from peer " + getPeerId());

					if (!firstSent()) {
						setFirstSent(true);
					} else {
						close();
						return;
					}
					Message.BitFieldMessage bMessage = (Message.BitFieldMessage)message;
					bitfield = bMessage.getData();
					peerCompleted = bMessage.getCompleted();
					for (int i = 0; i <peerCompleted.length; i++) {
						if (peerCompleted[i])
							client.have(i);
						else
							System.out.println("Peer did not complete " + i);
					}

					if (client.outfile.needPiece(bitfield) != -1) {
						interested = true;
						peer_choked = false;
						jobQueue.offer(Message.INTERESTED);
					}
					break;
				case Message.REQUEST_ID:
					try {
						System.out.println("Got request message from peer " + getPeerId());
						Message.RequestMessage rMessage = (Message.RequestMessage)message;
						int fileOffset = rMessage.getIndex() * client.tracker.getTorrentInfo().piece_length + rMessage.getOffset();
						byte[] data = new byte[rMessage.getLength()];
						f.read(data, fileOffset, data.length);
						Message piece = new Message.PieceMessage(rMessage.getIndex(), rMessage.getOffset(), data);
						uploaded+=piece.getLength();
						setLastUploaded(piece.getLength());
						jobQueue.offer(piece);
					} catch (IOException e) {
						System.out.println(e.getMessage());
					}
					break;
				case Message.PIECE_ID:
					//	try {
					System.out.println("Got piece message from peer " + getPeerId());

					Message.PieceMessage pMessage = (Message.PieceMessage)message;
					System.out.println("this piece " + pMessage.getPieceIndex() + " " + pMessage.getOffset() + " " + pMessage.getPieceLength());
					client.outfile.completed[pMessage.getPieceIndex()].first = true;
					downloaded+=pMessage.getLength();
					setLastDownloaded(pMessage.getLength());
					requestNextPiece(pMessage);

					break;
				}
			}
			try {
				f.close();
			} catch (IOException e) {
				System.out.println(e.getMessage());
			}
		}
	}

	private class Consumer implements Runnable {
		public void run() {
			while (true) {
				Message message = jobQueue.poll();
				if (message != null) { //Queue is not empty
					if (message.isNull()) {	//Signal to close thread
						stopProducing = true;
						break;
					}
					synchronized (lock) {
						while (message.getID() != Message.INTERESTED_ID && message.getID() != Message.BITFIELD_ID && choked) {
							System.out.println("Started waiting");
							try { lock.wait(); } catch (InterruptedException e) {
								System.out.println("INTERRUPTED");
								break;
							}
							System.out.println("Woke up");
						}
					}
					try {
						System.out.println("Writing message: " + message.getID());
						Message.encode(toPeer, message);
					} catch (IOException e) {
						System.out.println("Caught IO Exception trying to encode message");
						break;
					}
				}
			}
		}
	}

	private void requestNextPiece(Message.PieceMessage pMessage) {

		client.outfile.addBlock(pMessage);
		int last_block_length = (client.tracker.getTorrentInfo().file_length%client.tracker.getTorrentInfo().piece_length)%max_length;
		int piece = pMessage.getPieceIndex();
		int offset = pMessage.getOffset();


		if (piece == (client.tracker.getTorrentInfo().piece_hashes.length - 1)) {

			if (last_block_length + offset == client.tracker.getTorrentInfo().file_length % client.tracker.getTorrentInfo().piece_length) {
				if (client.outfile.write(piece)) {
					downloaded += client.outfile.pieces[piece].getData().length;


					jobQueue.offer(new Message.HaveMessage(piece));
					client.completed(piece);

					return;
				} else {
					System.out.println("SHA FAILED"); System.exit(1); 
				}
			} else {
				jobQueue.offer(new Message.RequestMessage(piece, max_length + offset, last_block_length));
			}
		} else if ( max_length + offset == client.tracker.getTorrentInfo().piece_length) {
			if (client.outfile.write(piece)) {
				System.out.println("SHA SUCCESS");
				downloaded += client.outfile.pieces[piece].getData().length;
				jobQueue.offer(new Message.HaveMessage(piece));
				client.completed(piece);

				Message.RequestMessage m = formRequest();
				jobQueue.offer(m);
				System.out.println("just sent request for piece " + m.getIndex());

			} else {
				System.out.println("SHA FAILED");
			}
		} else {

			jobQueue.offer(new Message.RequestMessage(piece, max_length + offset, max_length));
		}

	}
	
	private Message.RequestMessage formRequest() {

		int piece;
		int offset = 0;

		if ((piece = client.outfile.needPiece(bitfield)) == -1) { 

			interested = false;
			return null;
		}

		if (client.outfile.pieces[piece].getOffset() != 0) {
			offset += max_length;
		}

		System.out.println("forming request for piece " + piece);
		return (new Message.RequestMessage(piece, offset, max_length));
	}
	
	public void startThreads() {

		this.jobQueue = new ConcurrentLinkedQueue<Message>();

		doHandshake();

		if (!checkHandshake(client.tracker.getTorrentInfo().info_hash.array())) {
			System.out.println("handshake failed");
			close();
			client.peerList.remove(this);
			return;
		}

		//jobQueue.offer(new Message.BitFieldMessage(this.client.outfile.client_bitfield));
		this.producer = new Thread(this.new Producer());
		this.consumer = new Thread(this.new Consumer());
		this.producer.start();
		this.consumer.start();

	}

	public boolean addJob(Message message) {
		return jobQueue.offer(message);
	}

	private boolean firstSent() {
		return first_sent;
	}

	private void setFirstSent(boolean first_sent) {
		this.first_sent = first_sent;
	}

	public Peer(String ip, String peer_id, int port) {

		this.peer_ip = ip;
		this.peer_id = peer_id;
		this.port = port;
		this.choked = true;
		this.peer_choked = true;
		this.connected = false;
		this.interested = false;
		this.first_sent = false;
		this.stopProducing = false;
	}

	public void setClient(RUBTClient client) {

		this.client = client;
		this.peerCompleted = new boolean[client.outfile.completed.length];
		pieces = new Piece[client.tracker.getTorrentInfo().piece_hashes.length];
		int i;

		for (i = 0; i < pieces.length - 1; i++) {
			pieces[i] = new Piece(client.tracker.getTorrentInfo().piece_length);
		}

		int last_piece_length = client.tracker.getTorrentInfo().file_length % client.tracker.getTorrentInfo().piece_length;

		if (last_piece_length == 0) {

			pieces[i] = new Piece(client.tracker.getTorrentInfo().piece_length);
		}  else  {
			pieces[i] = new Piece(last_piece_length);
		}

	}


	public String getIP() {
		return peer_ip;
	}

	public String getPeerID() {
		return peer_id;
	}

	public int getPort() {
		return port;
	}

	public boolean isChoked() {
		return peer_choked;
	}
	
	public void setChoked(boolean b) {
		peer_choked = b;
	}
	
	public int getLastUploaded() {
		return last_uploaded;
	}

	public void setLastUploaded( int x ) {
		last_uploaded = x;
	}

	public int getLastDownloaded() {
		return last_downloaded;
	}

	public void setLastDownloaded(int x) {
		last_downloaded = x;
	}



	public boolean listenForUnchoke() {

		try {
			if (fromPeer.read() == 1 && fromPeer.read() == 1) {

				return true;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}


	public boolean connectToPeer() {

		try {

			this.peerSocket = new Socket(peer_ip, port);
			this.peerSocket.setSoTimeout(180*1000);				//3 minute timeout
			this.toPeer = new DataOutputStream(peerSocket.getOutputStream());
			this.fromPeer = new DataInputStream(peerSocket.getInputStream());
		} catch(UnknownHostException e) {
			System.err.println("Unknown Host " + e.getMessage());
			return false;
		} catch(IOException e) {
			System.err.println("IO Exception " + e.getMessage());
			return false;
		}

		connected = true;
		return true;
	}

	public void close() {

		try {

			if (peerSocket != null) peerSocket.close();

			if (toPeer != null) toPeer.close();

			if (fromPeer != null) fromPeer.close();

			connected = false;

		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	private void sendMessage(byte[] message) {

		try {
			this.toPeer.write(message);

		} catch(IOException e) {
			System.err.println("IO Exception in sendMessage " + e.getMessage());
		}


	}

	public void sendKeepAlive() {

		sendMessage(Message.keep_alive);
	}

	public void sendInterested() {

		sendMessage(Message.interested);

	}

	public void sendUninterested() {

		sendMessage(Message.uninterested);

	}



	public Message listen() {

		return null;
	}

	public void doHandshake() {

		sendMessage(Message.handshake(client.tracker.getPeerId().getBytes(), client.tracker.getTorrentInfo().info_hash.array()));

	}

	public String getPeerId() {
		return peer_id;
	}

	public boolean checkHandshake(byte[] info_hash) {
		byte[] response_hash = new byte[20];
		byte[] response = new byte[HEADER_SIZE];

		try {


			fromPeer.read(response);
			try {
				System.out.println("Response: " + new String(response, "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				System.out.println("Unsupported Encoding");
			}


			System.arraycopy(response, 28, response_hash, 0, 20);
			for (int i = 0; i < 20; i++) {
				if (response_hash[i] != info_hash[i]) {
					System.out.println(Arrays.toString(response_hash));
					return false;
				}
			}
		} catch(EOFException e) {
			System.err.println("EOF Exception " + e.getMessage());
			return false;
		} catch (IOException e) {
			System.err.println("IOException " + e.getMessage());
			return false;
		}

		return true;
	}

	public boolean handshakeCheck(byte[] peer_handshake) {

		byte[] peer_info_hash = new byte[20];

		System.arraycopy(peer_handshake, 28, peer_info_hash, 0, 20);

		byte[] peer_id = new byte[20];

		System.arraycopy(peer_handshake,48,peer_id,0,20);//copies the peer id.

		if (Arrays.equals(peer_info_hash, this.client.tracker.getTorrentInfo().info_hash.array())) return true;

		return false;

	}



	@Override
	public String toString() {
		return String.format("ID: %s\nIP: %s\nPort: %d", peer_id, peer_ip, port);
	}
}
