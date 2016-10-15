/**
 * 
 * Encapsulates a Piece object, used to store downloaded pieces. 
 *
 */
public class Piece {

	private byte[] data;
	int offset;
	
	/**
	 * Constructor takes size of piece in bytes
	 * @param size
	 */
	public Piece(int size) {
		data = new byte[size];
		this.offset = 0;
	}
	
	/**
	 * Add piece data to byte[] data
	 * @param offset within byte[]
	 * @param piece data
	 */
	public void addPiece(int offset, byte[] data) {
		
		for (int i = 0; i < data.length; i++) {
		
			this.data[offset+i] = data[i];
		}
		
		this.offset = offset;
	}
	/**
	 * Get piece data
	 * @return byte[] data
	 */
	public byte[] getData() {
		return data;
	}
	
	/**
	 * Get offset within byte[] data last written to. Equals 0 before first block downloaded, piece_length afterwards. 
	 * @return offset
	 */
	public int getOffset() {
		return offset;
	}

}
