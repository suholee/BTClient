import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import GivenTools.TorrentInfo;


/**
 * 
 *	Maintains state information about output file. computes client bitfield based on downloaded pieces
 *
 */
public class OutFile {

	public int sum = 0;
	private RandomAccessFile file;
	private TorrentInfo torrent;
	public byte[] client_bitfield;
	public Piece[] pieces;
	private RUBTClient client;
	private int incomplete;
	private int file_size;
	private String filename;
	private boolean created;

	public Completed[] completed; 

	//keep track of which pieces are in progress
	public class Completed {
		public boolean first;
		public boolean second;
		public Completed() {
			this.first = false;
			this.second = false;
		}
	}


	public OutFile(TorrentInfo torrent) {
		this.torrent = torrent;
		file_size = torrent.file_length;
		incomplete = file_size;
		filename = torrent.file_name;
		created = false;
		pieces = new Piece[torrent.piece_hashes.length];

		int i;
		for (i = 0; i < pieces.length - 1; i++) {
			pieces[i] = new Piece(torrent.piece_length);
		}

		int last_piece_length = torrent.file_length % torrent.piece_length;

		if (last_piece_length == 0) {

			pieces[i] = new Piece(torrent.piece_length);
		}  else  {
			pieces[i] = new Piece(last_piece_length);
		}

		if (pieces.length % 8 == 0) {
			client_bitfield = new byte[pieces.length/8];
		} else {
			client_bitfield = new byte[pieces.length/8 + 1];
		}

		initializeBitField();

		this.completed = new Completed[torrent.piece_hashes.length];
		for (i = 0; i < this.completed.length; i ++) {
			this.completed[i] = new Completed();
		}

	}

	/**
	 * Set associated client app to RUBTClient client
	 * @param client
	 */
	public void setClient(RUBTClient client) {
		this.client = client;
	}


	public void createFile() {
		try {
			file = new RandomAccessFile(filename, "rw");
			created = true;
		} catch (FileNotFoundException e) {
			System.out.println("FileNotFoundException initializing RAF " + e.getMessage());
		} 
	}
	public int loadState() {

		byte[] piece = null;
		int complete = 1;
		
		if (!created) createFile();

		for (int i = 0; i < pieces.length; i++) {
			if (i == (pieces.length - 1)) {
				piece = new byte[torrent.file_length%torrent.piece_length];
			} else {
				piece = new byte[torrent.piece_length];
			}

			try {
				file.seek(torrent.piece_length*i);
				file.read(piece);
				if (verifyPiece(piece) == i) {
					completed[i].first = true;
					completed[i].second = true;
					incomplete -= pieces[i].getData().length;
				} else {
					complete = - 1;
					completed[i].first = false;
					completed[i].second = false;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			
		}
		
		updateBitfield();
	
		return complete;
	}

	/**
	 * adds first block of piece message to to Piece[] array. 
	 * @param pMessage
	 */

	public void addBlock(Message.PieceMessage pMessage) {



		pieces[pMessage.getPieceIndex()].addPiece(pMessage.getOffset(), pMessage.getPiece());

		if (pMessage.getOffset() == 0) {
			completed[pMessage.getPieceIndex()].first = true;
		} else {
			completed[pMessage.getPieceIndex()].second = true;
		}



	}

	/**
	 * Finds and returns piece index of the first piece peer has and client does not
	 * @param peer_bitfield
	 * @return index of first piece client does not have
	 */
	public int needPiece(byte[] peer_bitfield) {

		//int piece_index = 0;

		for (int i = 0; i < torrent.piece_hashes.length; i++) {
			int m = i%8;
			int byte_index = (i-m) / 8;

			if ((client_bitfield[byte_index] >> (7-m) & 1) != 1){
				if ((peer_bitfield[byte_index] >> (7-m) & 1) == 1) {
					if (!completed[i].first && !completed[i].second) {
						return i;
					}
				}
			}
		}
		return -1;
	}

	/**
	 * Called when Piece is already written to Piece[]. Writes piece data at piece_index to RAF
	 * @param piece_index
	 * @return true if piece hashes correctly and writes successfully
	 */
	public boolean write(int piece_index) {

		if (verifyPiece(pieces[piece_index].getData()) == -1) {
			return false;
		}
		try {
			System.out.println("WRITING PIECE " + piece_index);
			sum+=piece_index;
			file.seek((long)piece_index*torrent.piece_length);
			file.write(pieces[piece_index].getData());
			completed[piece_index].second = true;
			this.client.setDownloaded(pieces[piece_index].getData().length);
			
			incomplete -= pieces[piece_index].getData().length;
			updateBitfield();

			if (incomplete <= 0 || piece_index == torrent.file_length% torrent.piece_length) {		// done downloading
				client.tracker.update(client.uploaded, client.downloaded);
				client.tracker.constructURL("completed");
				System.out.println("completed");
				
				client.tracker.sendEvent("completed");
				close();
			}


			return true;
		} catch(IOException e) {
			System.err.println("IO exception writing to RAF " + e.getMessage());
		}

		return false;
	}


	public void close() {

		try {
			file.close();
		} catch(IOException e) {
			System.err.println("IOException closing RAF " + e.getMessage());
		}
	}


	public void updateBitfield() {

		for (int i = 0; i < pieces.length; i++) {
			int m = i%8;
			int byte_index = (i-(m))/8;
			if (completed[i].first && completed[i].second) {

				client_bitfield[byte_index] |= (1 << (7-m));
			} else {
				client_bitfield[byte_index] &= ~(1 << (7-m));
			}

		}
	}

	private int verifyPiece(byte[] message) {

		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			System.err.println("No such algorithm " + e.getMessage());
			return -1;
		}


		byte[] piece_hash = md.digest(message);


		md.update(piece_hash);

		for (int i = 0; i < torrent.piece_hashes.length; i++) {
			if (Arrays.equals(piece_hash, torrent.piece_hashes[i].array())) {
				return i;
			}
		}


		return -1;
	}

	private void initializeBitField() {


		for (int i = 0; i < client_bitfield.length; i++) {

			for (int j = 1; j <= 8; j++) {
				client_bitfield[i] &= ~(1  << j); 
			}
		}

	}
}


