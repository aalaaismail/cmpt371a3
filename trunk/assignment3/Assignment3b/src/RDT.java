
/**
 * @author mohamed
 *
 */


import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;


public class RDT {

	public static final int MSS = 100; // Max segement size in bytes
	public static final int RTO = 500; // Retransmission Timeout in msec
	public static final int ERROR = -1;
	public static final int MAX_BUF_SIZE = 3;  
	public static final int GBN = 1;   // Go back N protocol
	public static final int SR = 2;    // Selective Repeat
	public static int protocol = GBN;
	
	public static double lossRate = 0.0;
	public static Random random = new Random(); 
	public static Timer timer = new Timer();
	public static int TimeoutDelay = 1000; //1000ms
	
	private DatagramSocket socket; 
	private InetAddress dst_ip;
	private int dst_port;
	private int local_port; 
	
	private RDTBuffer sndBuf;
	private RDTBuffer rcvBuf;
	
	private ReceiverThread rcvThread; 
	
	public TimeoutHandler timeoutHandlers[];
	
	RDT (String dst_hostname_, int dst_port_, int local_port_) 
	{
		local_port = local_port_;
		dst_port = dst_port_; 
		try {
			 socket = new DatagramSocket(local_port);
			 dst_ip = InetAddress.getByName(dst_hostname_);
		 } catch (IOException e) {
			 System.out.println("RDT constructor: " + e);
		 }
		sndBuf = new RDTBuffer(MAX_BUF_SIZE);
		if (protocol == GBN)
			rcvBuf = new RDTBuffer(1);
		else 
			rcvBuf = new RDTBuffer(MAX_BUF_SIZE);
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port);
		rcvThread.start();
	}
	
	//for specifying the send/receiver buffer sizes
	RDT (String dst_hostname_, int dst_port_, int local_port_, int sndBufSize, int rcvBufSize, int protocol_)
	{
		local_port = local_port_;
		dst_port = dst_port_;
		 try {
			 //create a new udp socket
			 socket = new DatagramSocket(local_port);
			 
			 //looks up the destnation IP address
			 dst_ip = InetAddress.getByName(dst_hostname_);
			 
			 
		 } 
		 
		 catch (IOException e) 
		 {
			 System.out.println("RDT constructor: " + e);
		 }
		 
		 //set buffer to the one specified
		sndBuf = new RDTBuffer(sndBufSize);
		
		//set the timeouthandler array to the sndbuffer size
		//timeoutHandlers = new TimeoutHandler[sndBufSize];
		
		//set which protocol we are using
		protocol = protocol_;
		
		if (protocol == GBN)
			rcvBuf = new RDTBuffer(1);
		else if (protocol == SR)
			rcvBuf = new RDTBuffer(rcvBufSize);
		
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port);
		rcvThread.start();
	}
	
	public static void setLossRate(double rate) 
	{
		lossRate = rate;
	}
	
	// called by app
	// returns total number of sent bytes  
	public int send(byte[] data, int size) 
	{
		boolean done = false;
		
		//to keep track of the index of the data array
		int dataIndex = 0;
		//****** complete
		while (!done)
		{
			// divide data into segments
			RDTSegment seg = new RDTSegment();
			int i = 0;
			
			//split the data by the MSS
			for (i = 0; (i < MSS) && (i < seg.data.length); i++)
			{
				if(dataIndex == size)
				{
					done = true;
					break;
				}
				seg.data[i] = data[dataIndex];
				dataIndex++;
			}
			
			//we are done once we have gone through all the indicies of the data
			if (dataIndex == size)
				done = true;
			
			//set the headers
			// no flags or ack number since we are sending data
			seg.seqNum = sndBuf.next; //set sequence number
			
			//segment length is the maximum segement size + the header size
			seg.length = i + RDTSegment.HDR_SIZE;
			
			//set the receive window to the difference between the slots that are filled and the buffer size
			seg.rcvWin = rcvBuf.size - (rcvBuf.next%rcvBuf.size - rcvBuf.base%rcvBuf.size);
			
			//set the checksum and set it
			seg.checksum = seg.computeChecksum();
			
			//int indexNum = sndBuf.next%sndBuf.size;
			
			// put each segment into sndBuf
			if (protocol == GBN) //Go Back N
				sndBuf.putNext(seg);
			else //Selective Repeat
				sndBuf.putNext(seg);
			
			Utility.udp_send(sndBuf.getNext(), socket, dst_ip, dst_port);
			
			// schedule timeout for segment(s) 
			timer.schedule(new TimeoutHandler(sndBuf, sndBuf.getNext(), socket, dst_ip, dst_port), TimeoutDelay, TimeoutDelay);
			//if (sndBuf.buf[(indexNum%sndBuf.size)].ackReceived == true)
				//timer.cancel();
		}
		return size;
	}
	
	
	// called by app
	// receive one segment at a time
	// returns number of bytes copied in buf
	public int receive (byte[] buf, int size)
	{
		RDTSegment rdtSeg = new RDTSegment();
		
		rdtSeg = rcvBuf.receiveNext();
		
		for (int i = 0; i < rdtSeg.length; i++)
		{
			buf = rdtSeg.data;
		}
		return rdtSeg.length - RDTSegment.HDR_SIZE;   // fix
	}
	
	// called by app
	public void close() {
		// OPTIONAL: close the connection gracefully
		// you can use TCP-style connection termination process
	}
	
}  // end RDT class 


class RDTBuffer {
	public RDTSegment[] buf;
	public int size;	
	public int base;
	public int next;
	public Semaphore semMutex; // for mutual exclusion
	public Semaphore semFull; // #of full slots
	public Semaphore semEmpty; // #of Empty slots
	
	RDTBuffer (int bufSize) 
	{
		//rdt segment array
		buf = new RDTSegment[bufSize];
		
		//sets everything to null
		for (int i=0; i<bufSize; i++)
			buf[i] = null;
		
		
		size = bufSize;
		
		//base and next segment to be sent is set to 0
		base = next = 0;
		
		//create semaphores
		semMutex = new Semaphore(1, true);
		semFull =  new Semaphore(0, true);
		semEmpty = new Semaphore(bufSize, true);
	}

	
	
	// Put a segment in the next available slot in the buffer
	public void putNext(RDTSegment seg) 
	{		
		try 
		{
			
			semEmpty.acquire(); // wait for an empty slot 
			
			//to ensure only one thread is accessing the buffer at a time
			semMutex.acquire(); // wait for mutex 
				buf[next%size] = seg;
				next++;  
			semMutex.release();
			
			semFull.release(); // increase #of full slots
			
			
		} 
		catch(InterruptedException e) 
		{
			System.out.println("Buffer put(): " + e);
		}
	}
	
	public RDTSegment receiveNext()
	{
		RDTSegment seg = new RDTSegment(); 
		try 
		{
			
			semFull.acquire(); // wait for a full slot 
			
			//to ensure only one thread is accessing the buffer at a time
			semMutex.acquire(); // wait for mutex 
				buf[base%size] = seg;
				base++;  
			semMutex.release();
			
			semEmpty.release(); // increase #of empty slots
				
		} 
		catch(InterruptedException e) 
		{
			System.out.println("Buffer retreiveNext(): " + e);
		}
		
		return seg;
	}
	
	// return the next in-order segment
	public RDTSegment getNext() 
	{
		return buf[next%size];
	}
	
	// Put a segment in the *right* slot based on seg.seqNum
	// used by receiver in Selective Repeat
	public void putSeqNum (RDTSegment seg) 
	{
		try 
		{
			
			semEmpty.acquire(); // wait for an empty slot 
			
			//to ensure only one thread is accessing the buffer at a time
			semMutex.acquire(); // wait for mutex 
				buf[seg.seqNum%size] = seg;
				//next++;  
			semMutex.release();
			
			semFull.release(); // increase #of full slots
			
			
		} 
		catch(InterruptedException e) 
		{
			System.out.println("Buffer putSeqNum(): " + e);
		}

	}
	
	public boolean checkSeqNum(RDTSegment seg)
	{
		int compareNum = 0;
		try 
		{
			//to ensure only one thread is accessing the buffer at a time
			semMutex.acquire(); // wait for mutex
				if (buf[seg.seqNum%size] != null)
					compareNum = buf[seg.seqNum%size].seqNum;
				else
					compareNum = -1;
			semMutex.release();			
		} 
		catch(InterruptedException e) 
		{
			System.out.println("Buffer putSeqNum(): " + e);
		}
		
		return(seg.seqNum == compareNum || compareNum == -1);

	}
	
	// for debugging
	public void dump() {
		System.out.println("Dumping the receiver buffer ...");
		// Complete, if you want to 
		
	}
} // end RDTBuffer class



class ReceiverThread extends Thread {
	RDTBuffer rcvBuf, sndBuf;
	DatagramSocket socket;
	InetAddress dst_ip;
	int dst_port;
	
	ReceiverThread (RDTBuffer rcv_buf, RDTBuffer snd_buf, DatagramSocket s, 
			InetAddress dst_ip_, int dst_port_) {
		rcvBuf = rcv_buf;
		sndBuf = snd_buf;
		socket = s;
		dst_ip = dst_ip_;
		dst_port = dst_port_;
	}	
	public void run() {
		
		while(true)
		{
			byte[] data = new byte[RDT.MSS];
			
			//create a new packet for receiving
			DatagramPacket rcvpkt = new DatagramPacket(data, data.length);
			
			//receive a packet
			try
			{
				socket.receive(rcvpkt);
			}
			catch(Exception e)
			{
				System.out.println("ERROR " + e);
				System.exit(0);
			}
			
			// turn the received data into a segment
			RDTSegment rcvseg = new RDTSegment();
			makeSegment(rcvseg, rcvpkt.getData());
			
			// verify the checksum
			if(rcvseg.isValid()){
				
				// if the segment contains an ACK
				if(rcvseg.containsAck())
				{
					// if GBN
					if(RDT.protocol == 1)
					{
						// if ackNum is > than base it means it is a valid ack
						if(rcvseg.ackNum > sndBuf.base)
						{
							if(rcvseg.ackNum >= sndBuf.base)
								sndBuf.base = rcvseg.ackNum;
						}
					}
					
					// if SR
					else{
						// check if received ack has already been received
						if(sndBuf.buf[rcvseg.ackNum%sndBuf.size].ackReceived != true || 
								sndBuf.buf[rcvseg.ackNum%sndBuf.size] == null)
						{
							System.out.println("ACK received");

							// set flag to show it has been received
							sndBuf.buf[rcvseg.ackNum%sndBuf.size].ackReceived = true;
							
	
							// if it is the base then set the base to next unACKd segment
							if(rcvseg.ackNum == sndBuf.base)
							{
								int i = 1;
								// traverse buffer starting at base+1 looking for unreceived
								while(sndBuf.buf[(sndBuf.base+i)%sndBuf.size].ackReceived != false)
									i++;
								// set base to next unreceived segment
								sndBuf.base = sndBuf.base+i;
							}
						}
					}
				}
				
				// not ACK means it contains data
				else{
					
					if(rcvBuf.checkSeqNum(rcvseg))
					{
						// if GBN then put in next slot of buffer
						if(RDT.protocol == 1)
							rcvBuf.putNext(rcvseg);
						
						// if SR then put in correct index
						else
							rcvBuf.putSeqNum(rcvseg);
						
						// send ACK
						RDTSegment seg = new RDTSegment();
						seg.ackNum = rcvseg.seqNum;
						seg.flags = 16;
						seg.checksum = seg.computeChecksum();
						Utility.udp_send(seg, socket, dst_ip, dst_port);
						
					}
				}
			}
			
		}
	}
	
	
//	 create a segment from received bytes 
	void makeSegment(RDTSegment seg, byte[] payload) {
	
		seg.seqNum = Utility.byteToInt(payload, RDTSegment.SEQ_NUM_OFFSET);
		seg.ackNum = Utility.byteToInt(payload, RDTSegment.ACK_NUM_OFFSET);
		seg.flags  = Utility.byteToInt(payload, RDTSegment.FLAGS_OFFSET);
		seg.checksum = Utility.byteToInt(payload, RDTSegment.CHECKSUM_OFFSET);
		seg.rcvWin = Utility.byteToInt(payload, RDTSegment.RCV_WIN_OFFSET);
		seg.length = Utility.byteToInt(payload, RDTSegment.LENGTH_OFFSET);
		//Note: Unlike C/C++, Java does not support explicit use of pointers! 
		// we have to make another copy of the data
		// This is not effecient in protocol implementation
		for (int i=0; i< seg.length; i++)
			seg.data[i] = payload[i + RDTSegment.HDR_SIZE]; 
	}
	
} // end ReceiverThread class

