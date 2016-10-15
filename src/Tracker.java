import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import GivenTools.TorrentInfo;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

public class Tracker {
	private String info_hash;
	private String URL;
	private String peer_id;
	private String ip;
	private int port;
	private int uploaded;
	private int downloaded;
	private TorrentInfo torrent;
	
	URL tracker_url = torrent.announce_url;
	int tracker_port = tracker_url.getPort();
	String tracker_ip = tracker_url.getHost();
	
	/**
	 * Constructor takes TorrentInfo obj
	 * @param torr
	 */
	public Tracker(TorrentInfo torrent) {
		this.torrent = torrent;
		this.peer_id = generatePeerID();
		this.uploaded = 0;
		this.downloaded = 0;
		this.port = tracker_port;
		this.ip = tracker_ip;
	}
	
	public TorrentInfo getTorrentInfo() {
		return torrent;
	}
	
	public byte[] sendEvent(String event) {
		
		HttpURLConnection connection = sendGetRequest(event);

		DataInputStream inputstream = null;
		ByteArrayOutputStream bencoded_response = null;
		
		try {
			inputstream  = new DataInputStream(connection.getInputStream());
			bencoded_response = new ByteArrayOutputStream();

			int read_data;
			while ((read_data = inputstream.read()) != -1) {
				bencoded_response.write(read_data);
			}

			bencoded_response.close();

		} catch (IOException e) {
			System.err.println("IO Exception " + e.getMessage());
		}


		return bencoded_response.toByteArray();
	}
	
	private HttpURLConnection sendGetRequest(String event) {
		
		HttpURLConnection connected = null;
		
		try 
		{
			tracker_url.openConnection();
			connected = (HttpURLConnection) tracker_url.openConnection();
			connected.setRequestMethod("GET");
			
			return connected;
			
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} 
		
		return connected;
	}
	
	
	
	private static String generatePeerID() {
		String alphanumeric = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
		StringBuilder generatingPeerID = new StringBuilder();
		for(int i = 0; i < 20; i++){
			int chars = (int)(Math.random()*alphanumeric.length());
			generatingPeerID.append(alphanumeric.charAt(chars));
		}
		return generatingPeerID.toString();
	}
}
