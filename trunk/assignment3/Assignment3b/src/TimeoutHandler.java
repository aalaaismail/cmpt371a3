/**
 * @author mhefeeda
 *
 */


import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.TimerTask;

class TimeoutHandler extends TimerTask {
	RDTBuffer sndBuf;
	RDTSegment seg; 
	DatagramSocket socket;
	InetAddress ip;
	int port;
	
	TimeoutHandler (RDTBuffer sndBuf_, RDTSegment s, DatagramSocket sock, InetAddress ip_addr, int p) 
	{
		sndBuf = sndBuf_;
		seg = s;
		socket = sock;
		ip = ip_addr;
		port = p;
	}
	
	public void run() {
		
		System.out.println(System.currentTimeMillis()+ ":Timeout for seg: " + seg.seqNum);
		System.out.flush();
		if (seg.ackReceived)
			System.exit(0);
		
		// complete 
		switch(RDT.protocol){
			case RDT.GBN:
				//resend the same packet since GBN's window size is one
				Utility.udp_send(seg, socket, ip, port);
				
				break;
			case RDT.SR:
				//resend the segment that timed out
				Utility.udp_send(seg, socket, ip, port);
				break;
			default:
				System.out.println("Error in TimeoutHandler:run(): unknown protocol");
		}
		
	}
} // end TimeoutHandler class

