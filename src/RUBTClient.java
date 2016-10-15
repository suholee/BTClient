import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.net.URL;

import GivenTools.BencodingException;
import GivenTools.TorrentInfo;

public class RUBTClient{
	
	public Tracker tracker;

	public int uploaded;
	public int downloaded;
	public static boolean seeding;


	private static boolean keepRunning;
	private static int HEADER_SIZE = 68;

	public Queue<Peer> peer_queue;

	public String outputFile;

	public OutFile outfile;

	public List<Peer> peerList;
	public List<Peer> want_unchoke;
	public final int unchoke_limit = 3;
	private static int unchoked_peers;
	public List<Peer> interested_peers;
    private List<CompleteIndex> totalCompleted;
    
	private int unchoked;
	public final int unchoked_limit = 3;
    
	private class CompleteIndex {
		public int index;
		public int total;
		public boolean have;
		public CompleteIndex(int index, int total) {
			this.index = index;
			this.total = total;
			this.have = false;
		}
	}
	
	public Thread peerListener;
    
	/**
	 * Constructor for RUBTClient obj
	 * @param tracker
	 * @param outputFile
	 */

	public RUBTClient(Tracker tracker, String outputFile) {
		this.tracker = tracker;

		this.outputFile = outputFile;
		outfile = new OutFile(tracker.getTorrentInfo());
		keepRunning = true;
		seeding = false;
		unchoked_peers = 0;
		totalCompleted = new ArrayList<>();
		for (int i = 0; i < outfile.completed.length; i++) {
			totalCompleted.add(new CompleteIndex(i, 0));
		}
	}
	
	public static void main(String[] args) throws Exception {

		if (args.length != 2) {
			System.out.println("Usage: java -cp . RUBTClient <torrent-file> <outputfile> ");
			System.exit(0);
		}
		
		String torrent_file = args[0];
		String output_file = args[1];
		
		Path pathToFile = FileSystems.getDefault().getPath(torrent_file);
        //torrent_bytes = bencodedinfo
		byte[] torrent_bytes = Files.readAllBytes(pathToFile);
		TorrentInfo torrent = null;

		try
		{
			torrent = new TorrentInfo(torrent_bytes);

		} catch(BencodingException e) {
			e.printStackTrace();
		}
		
		Tracker tracker = new Tracker(torrent);
		ByteBuffer[] piece_hashes = tracker.getTorrentInfo().piece_hashes;
		int num_pieces = piece_hashes.length;
	
		Response response = new Response(tracker.sendEvent("started"));
		
		RUBTClient client = new RUBTClient(tracker, output_file);

		client.peerList = response.getValidPeers();
		client.peer_queue = new ConcurrentLinkedQueue<Peer>();
		client.want_unchoke = new ArrayList<Peer>();

		Peer peer = client.peerList.get(0);
		peer.setClient(client);
		client.peer_queue.add(peer);
		
		client.outfile.setClient(client);

		File file = new File(output_file);
		
		int complete = -1;
		if (file.exists()) {
			complete = client.outfile.loadState();

		} else {
			client.outfile.createFile();
		}

		if (complete == 1) seeding = true;
		
		 (new Thread(new Listener(client))).start();
		 (new Thread(new PeerListener(client))).start();
		 
		 while (true) {
			Peer p = client.peer_queue.poll();
			if (p == null) continue;
			p.connectToPeer();
			p.startThreads();
		}
	}
	
	private static class PeerListener implements Runnable {
		
		private RUBTClient client;
		
		public PeerListener(RUBTClient client) {
			this.client = client;
		}
		public void run() {
			int port = 6881;
			while (true) {
				try {
					ServerSocket serverSocket = new ServerSocket(port);
					Socket clientSocket = serverSocket.accept();
					
					DataInputStream fromPeer = new DataInputStream(clientSocket.getInputStream());

					byte[] response_id = new byte[20];
					byte[] response = new byte[68];

					boolean validHandshake = true;
					
					try {
						
						fromPeer.read(response);
						try {
							
							System.out.println("Response: " + new String(response, "UTF-8"));
						
						}catch (UnsupportedEncodingException e) {
							System.out.println("Unsupported Encoding");
						}

						System.arraycopy(response, 48, response_id, 0, 20);
						for (Peer peer : client.peerList) {
							boolean equal = true;
							for (int i = 0; i < 20; i++) {
								if (response_id[i] != peer.getPeerID().getBytes()[i])
									equal = false;
							}
							if (equal) {
								validHandshake = validHandshake && equal;
								break;
							}
						}
					} catch(EOFException e) {
						System.err.println("EOF Exception " + e.getMessage());
						break;
					} catch (IOException e) {
						System.err.println("IOException " + e.getMessage());
						break;
					}
					if (validHandshake) {
						Peer peer = new Peer(clientSocket.getInetAddress().toString(), new String(response_id, "UTF-8"), clientSocket.getPort());
						peer.setClient(client);
						peer.startThreads();
						client.peer_queue.add(peer);
					}
				} catch (SocketTimeoutException e) {
					port++;
					if (port > 6889)
						port = 6881;
				} catch (IOException e) {
					System.out.println("Caught IOException listening for new peers");
					break;
				}
			}
		}
	}


	private static class Listener implements Runnable {
		
		RUBTClient client;
		
		public Listener(RUBTClient client) {
			this.client = client;
		}
		public void run(){
			Scanner scanner = new Scanner(System.in);
			while(true){
				if(scanner.nextLine().equals("quit")){
					client.outfile.close();
					System.exit(1);
				}else{
					System.out.println("incorrect input. try typing \"quit\"");
				}
			}
		}
	}
	
}