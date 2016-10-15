import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;

import GivenTools.Bencoder2;
import GivenTools.BencodingException;

public class Response {
	
	@SuppressWarnings("rawtypes")
	private HashMap response;
	private ArrayList<Peer> peer_list;
	private ArrayList<Peer> valid_peers;
	private Integer interval;
	private Integer min_interval;
	
	/**
	 * Key used to retrieve the peer list from the tracker response.
	 */
	public final static ByteBuffer PEERS_KEY = ByteBuffer.wrap(new byte[]{ 'p', 'e', 'e', 'r','s'});

	/**
	 * Key used to retrieve the peer list from the tracker response.
	 */
	public final static ByteBuffer INTERVAL_KEY = ByteBuffer.wrap(new byte[]{ 'i', 'n', 't', 'e','r','v','a','l' });

	/**
	 * Key used to retrieve the peer id from the tracker response.
	 */
	public final static ByteBuffer PEER_ID_KEY = ByteBuffer.wrap(new byte[]{ 'p', 'e', 'e', 'r',' ','i','d'});

	/**
	 * Key used to retrieve the peer port from the tracker response.
	 */
	public final static ByteBuffer PEER_PORT_KEY = ByteBuffer.wrap(new byte[]{ 'p', 'o', 'r', 't'});

	/**
	 * Key used to retrieve the peer ip from the tracker response.
	 */
	public final static ByteBuffer PEER_IP_KEY = ByteBuffer.wrap(new byte[]{ 'i', 'p'});
	/**
	 * Constructor takes bencoded tracker reply, parses, and initializes fields
	 * @param bencoded_response
	 */
	
	@SuppressWarnings("rawtypes")
	public Response(byte[] bencoded_response) {
		
		try {
			this.response = (HashMap) Bencoder2.decode(bencoded_response);
		} catch (BencodingException e) {
			System.err.println("Error decoding tracker response. " + e.getMessage());
		}
		
		this.interval = (Integer) response.get(INTERVAL_KEY);
		
		peer_list = new ArrayList<Peer>(20);
		
		ArrayList temp_peer_list = (ArrayList) response.get(PEERS_KEY);
		// Store all Peer information: IP ID PORT into one Peer Instance
		for (int i = 0; i < temp_peer_list.size(); i++) {
			HashMap temp = (HashMap) temp_peer_list.get(i);
			String peer_id = new String(((ByteBuffer)temp.get(PEER_ID_KEY)).array());
			String peer_ip = new String(((ByteBuffer)temp.get(PEER_IP_KEY)).array());
			int peer_port = (Integer) temp.get(PEER_PORT_KEY);
			Peer peer = new Peer(peer_ip, peer_id, peer_port);
			peer_list.add(peer);
		}
	}
	
	public ArrayList<Peer> getValidPeers() {
		
		ArrayList<Peer> valid_peers = new ArrayList<Peer>(2);
		for (int i = 0; i < peer_list.size(); i++) {
			if (peer_list.get(i).getPeerID().startsWith("RU")) {
				valid_peers.add(peer_list.get(i));
			}
		}
		return valid_peers;
	}
}
