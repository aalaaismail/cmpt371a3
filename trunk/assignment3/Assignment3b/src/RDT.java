
/**
 * @author mohamed
 *
 */


import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;


public class RDT {

	public static final int MSS = 10; // Max segement size in bytes
	public static final int RTO = 500; // Retransmission Timeout in msec
	public static final int ERROR = -1;
	public static final int MAX_BUF_SIZE = 3;  
	public static final int GBN = 1;   // Go back N protocol
	public static final int SR = 2;    // Selective Repeat
	public static int protocol = GBN;
	
	public static double lossRate = 0.0;
	public static Random random = new Random(); 
	public static Timer timer = new Timer();
	
	private DatagramSocket socket; 
	private InetAddress dst_ip;
	private int dst_port;
	private int local_port; 
	
	private RDTBuffer sndBuf;
	private RDTBuffer rcvBuf;
	
	private ReceiverThread rcvThread; 
	
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
		//set the global variables to the parameters
		local_port = local_port_;
		dst_port = dst_port_;
		 try 
		 {
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
		
		//set which protocol we are using
		protocol = protocol_;
		
		//set the receive buffer size
		if (protocol == GBN)
			rcvBuf = new RDTBuffer(rcvBufSize);
			//rcvBuf = new RDTBuffer(1);
		else if (protocol == SR)
			rcvBuf = new RDTBuffer(rcvBufSize);
		
		//start the receiver thread that receives acks and data for the sender and receiver respectively
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port);
		rcvThread.start();
	}
	
	//set the loss rate
	public static void setLossRate(double rate) 
	{
		lossRate = rate;
	}
	
	// called by app
	// returns total number of sent bytes  
	public int send(byte[] data, int size) 
	{
		//keep looping until we are done
		boolean done = false;
		
		//to keep track of the index of the data array
		int dataIndex = 0;
		
		//while we are not done
		while (!done)
		{
			// divide data into segments
			RDTSegment seg = new RDTSegment();
			int i = 0;
			
			//split the data into segments of at most MSS
			for (i = 0; (i < MSS && i < size); i++)
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
			
			// put each segment into sndBuf
			if (protocol == GBN) //Go Back N
				sndBuf.putNext(seg);
			else //Selective Repeat
				sndBuf.putNext(seg);
			
			//send the segment
			Utility.udp_send(seg, socket, dst_ip, dst_port);
			
			// schedule timeout for segment(s) 
			timer.schedule(new TimeoutHandler(sndBuf, seg, socket, dst_ip, dst_port), RTO, RTO);
			
		}
		return size;
	}
	
	
	// called by app
	// receive one segment at a time
	// returns number of bytes copied in buf
	public int receive (byte[] buf, int size)
	{
		//make a variable to save data
		RDTSegment rdtSeg;
		
		//set that variable to the next one to be removed
		rdtSeg = rcvBuf.receiveNext();
		
		//set the buffer to the data
		for (int i = 0; i < (rdtSeg.length - RDTSegment.HDR_SIZE); i++)
			buf[i] = rdtSeg.data[i];
		
		//return the size of the data
		return (rdtSeg.length - RDTSegment.HDR_SIZE); 
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
		
		//set the size of the buffer
		size = bufSize;
		
		//base and next segment to be sent is set to 0 initially
		base = next = 0;
		
		//create semaphores
		semMutex = new Semaphore(1, true);  //lock for the buffer
		semFull =  new Semaphore(0, true);  //keeps track of elements inside buffer
		semEmpty = new Semaphore(bufSize, true);  //keeps track of the number of empty slots
	}

	
	
	// Put a segment in the next available slot in the buffer
	public void putNext(RDTSegment seg) 
	{		
		try 
		{
			//System.out.println("putting item in buffer");
			semEmpty.acquire(); // wait for an empty slot 

			//to ensure only one thread is accessing the buffer at a time
			semMutex.acquire(); // wait for mutex 
				buf[next%size] = seg;  //put the segment into the buffer
				next++;  //increase the next
			semMutex.release();
			
			semFull.release(); // increase #of full slots
			//System.out.println("putting item in buffer2");
			
			
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
			
			semFull.acquire(); //drop a full slot
			//to ensure only one thread is accessing the buffer at a time
			semMutex.acquire(); // wait for mutex 
				seg = buf[base%size];	//take out the segment in the base slot
				System.out.println("MOVING BASE");
				base++;  //increase the base
			semMutex.release();  //release the buffer			
			semEmpty.release(); // increase #of empty slots
			System.out.println("Taking segment " + seg.seqNum + " outta buffer");
				
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
			buf[seg.seqNum%size] = seg;  //put segment in the right slot
			int i = 0;  //for index counting
			if (seg.seqNum == base) //if the segment is the base we can move the window
			{
				//System.out.println("Seg is equal to base");
				while(buf[(base+i)%size] != null && //if the segment is not null
						i < size && //and we are not going over the size of the buffer
						//and if the sequence number in the buffer is equal to base+i that means we received that data as well
						buf[(base+i)%size].seqNum == (base+i)) 
				{
					System.out.println("RELEASING");
					semFull.release(); // increase #of full slots.. so the application can grab more data
					i++;
					Thread.yield();
				}
			}
			//release buffer mutex
			semMutex.release();
			
			System.out.println("Empty = " + semEmpty.availablePermits() + " Full = " 
					+ semFull.availablePermits());
			
			//System.out.println("putseqNum success!");
			}
		catch(InterruptedException e) 
		{
			System.out.println("Buffer putSeqNum(): " + e);
		}
	}
	
	//returns false if the segment is already inside the buffer
	public boolean checkSeqNum(RDTSegment seg)
	{
		int compareNum = -1;
		try 
		{
			//to ensure only one thread is accessing the buffer at a time
			semMutex.acquire(); // wait for mutex
			
				//if the buffer is not null grab the sequence number inside the buffer
				if (buf[seg.seqNum%size] != null)
					compareNum = buf[seg.seqNum%size].seqNum; //grab the sequence number in that slot
			semMutex.release();			
		} 
		catch(InterruptedException e) 
		{
			System.out.println("Buffer checkSeqNum(): " + e);
		}
		//System.out.println(compareNum);
		
		//return true if the seg.seqNum is not equal to the one in the buffer or if the one in the buffer is null
		return(seg.seqNum != compareNum || compareNum == -1);

	}
	
	//sets the segment ack boolean to true
	public void ackSegment(int ackNum)
	{
		try 
		{
			//to ensure only one thread is accessing the buffer at a time
			semMutex.acquire(); // wait for mutex
			
				//if the buffer is not null set the ackReceived to true
				if (buf[ackNum%size] != null)
					buf[ackNum%size].ackReceived = true;
			semMutex.release();			
		} 
		catch(InterruptedException e) 
		{
			System.out.println("Buffer ackSegment(): " + e);
		}

	}
	
	// for debugging
	public void dump() {
		System.out.println("Dumping the buffer ...");
		// Complete, if you want to
		for(int i = 0; i < size; i++)
		{
			if(buf[i] == null)
				System.out.print("null ");
			else
				System.out.print(buf[i].seqNum + " ");
		}
		System.out.println();
		
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
			byte[] data = new byte[RDT.MSS+RDTSegment.HDR_SIZE];
			
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
			if(rcvseg.isValid())
			{
				//System.out.println("PACKET VALID");
				System.out.println(" RECEIEVED seqNum="
						+ rcvseg.seqNum + "  ackNum=" + rcvseg.ackNum + "  flags=" + rcvseg.flags + "  length=" + rcvseg.length);
				// if the segment contains an ACK
				if(rcvseg.containsAck())
				{
					System.out.println("ACK RECEIVED");
					// if GBN
					if(RDT.protocol == 1)
					{
						// if ackNum is >= than base it means it is a valid ack
						if(rcvseg.ackNum >= sndBuf.base)
						{
							System.out.println("PROCESSING ACK");
							
							//loops through the buffer processing each ack
							for(int i = sndBuf.base; i <= rcvseg.ackNum; i++ )
							{
								//set boolean in buffer to true
								sndBuf.ackSegment(i);
								
								try
								{
									sndBuf.semFull.acquire(); //number of full slots decrease
									sndBuf.semEmpty.release(); //number of empty slots increases
								}
								catch(InterruptedException e) 
								{
									System.out.println("Reciever thread run(): " + e);
								}
								
							}
							//set the new base
							sndBuf.base = rcvseg.ackNum + 1;  
						}
					}
					
					// if SR 
					else
					{
						// check if received ack has already been received
						if(sndBuf.buf[rcvseg.ackNum%sndBuf.size] == null ||  //segment in the buffer isnt null
								!sndBuf.buf[rcvseg.ackNum%sndBuf.size].ackReceived &&  //we havent received the ack for that segment yet
								sndBuf.buf[rcvseg.ackNum%sndBuf.size].seqNum == rcvseg.ackNum)  //the segment's sequence number is the same as the ack number
						{
							System.out.println("PROCESSING ACK");
							// set flag to show it has been received
							//sndBuf.buf[rcvseg.ackNum%sndBuf.size].ackReceived = true;
							sndBuf.ackSegment(rcvseg.ackNum);
							//System.out.println("marked as true");
							// if it is the base then set the base to next unACKd segment
							if(rcvseg.ackNum == sndBuf.base)
							{
								int i = 1;
								try
								{
									sndBuf.semFull.acquire();
									sndBuf.semEmpty.release();
								}
								catch(InterruptedException e) 
								{
									System.out.println("Buffer putSeqNum(): " + e);
								}
								// traverse buffer starting at base+1 looking for unreceived
								while(sndBuf.buf[(sndBuf.base+i)%sndBuf.size] != null && //the slot isnt null
										sndBuf.buf[(sndBuf.base+i)%sndBuf.size].ackReceived &&  // we received an ack for this slot
										i < sndBuf.size) //we arent going over the buffer size
								{
									i++;  //to move to the next buffer slot
									try
									{
										sndBuf.semFull.acquire();  //decrease the number of full slots
										sndBuf.semEmpty.release();  //increase the number of empty slots
									}
									catch(InterruptedException e) 
									{
										System.out.println("Buffer putSeqNum(): " + e);
									}
									Thread.yield();
									
								}
								// set base to next unreceived segment
								sndBuf.base = sndBuf.base+i;
							}
							//System.out.println("found next base");


						}
						sndBuf.dump();
					}
				}
				
				// not ACK means it contains data
				else
				{
					System.out.println("THERES DATA");
					rcvBuf.dump(); // dump data for testing
					System.out.println("BASE = " + rcvBuf.base);
					//System.out.println("length = " + rcvseg.length);
					//check if we already received this data
					if(rcvBuf.checkSeqNum(rcvseg) && RDT.protocol == 1 || rcvBuf.checkSeqNum(rcvseg) && RDT.protocol == 2 && rcvseg.seqNum >= rcvBuf.base && rcvseg.seqNum < (rcvBuf.base + rcvBuf.size))
					{
						//System.out.println("Putting in segment " + rcvseg.seqNum + " inside " + rcvseg.seqNum%rcvBuf.size + "with data " + rcvseg.data[0]
						//+ " length=" + rcvseg.length);
						// if GBN then put in next slot of buffer
						System.out.println("Putting in buffer");
						if(RDT.protocol == 1)
						{
							//if the sequence number is the base then put it in the buffer.. else drop
							if(rcvseg.seqNum <= rcvBuf.base)
							{
								System.out.println("SENDING ACK");
								rcvBuf.putNext(rcvseg);
								// send ACK
								RDTSegment seg = new RDTSegment();
								seg.ackNum = rcvseg.seqNum;
								seg.flags = 16;
								seg.length = RDTSegment.HDR_SIZE;
								seg.checksum = seg.computeChecksum();
								Utility.udp_send(seg, socket, dst_ip, dst_port);
							}
						}
						
						// if SR then put in correct index
						// only receive data in the rcv window size
						else if (RDT.protocol == 2 && rcvseg.seqNum >= rcvBuf.base && rcvseg.seqNum < (rcvBuf.base + rcvBuf.size))
						{
							System.out.println("SENDING ACK");
							rcvBuf.putSeqNum(rcvseg);
							 //send ACK
							RDTSegment seg = new RDTSegment();
							seg.ackNum = rcvseg.seqNum;
							seg.flags = 16;
							seg.length = RDTSegment.HDR_SIZE;
							seg.checksum = seg.computeChecksum();
							Utility.udp_send(seg, socket, dst_ip, dst_port);
						}
					}
					
					//if GBN and we already received this segment (its less than the base), send an ACK
					else if (RDT.protocol == 1 && rcvseg.seqNum <= rcvBuf.base)
					{
						// send ACK
						RDTSegment seg = new RDTSegment();
						seg.ackNum = rcvseg.seqNum;
						seg.flags = 16;
						seg.length = RDTSegment.HDR_SIZE;
						seg.checksum = seg.computeChecksum();
						Utility.udp_send(seg, socket, dst_ip, dst_port);
					}
					//always send ack if we in SR
					else if (RDT.protocol == 2 && rcvseg.seqNum < (rcvBuf.base + rcvBuf.size) )
					{
						System.out.println("SENDING ACK 2");
						// send ACK
						RDTSegment seg = new RDTSegment();
						seg.ackNum = rcvseg.seqNum;
						seg.flags = 16;
						seg.length = RDTSegment.HDR_SIZE;
						seg.checksum = seg.computeChecksum();
						Utility.udp_send(seg, socket, dst_ip, dst_port);
					}
					
				}
			}
			else
				System.out.println("CHECKSUM FAILURE");
		}
	}
	
	
//	 create a segment from received bytes 
	void makeSegment(RDTSegment seg, byte[] payload) 
	{	
		
		seg.seqNum = Utility.byteToInt(payload, RDTSegment.SEQ_NUM_OFFSET);
		seg.ackNum = Utility.byteToInt(payload, RDTSegment.ACK_NUM_OFFSET);
		seg.flags  = Utility.byteToInt(payload, RDTSegment.FLAGS_OFFSET);
		seg.checksum = Utility.byteToInt(payload, RDTSegment.CHECKSUM_OFFSET);
		seg.rcvWin = Utility.byteToInt(payload, RDTSegment.RCV_WIN_OFFSET);
		seg.length = Utility.byteToInt(payload, RDTSegment.LENGTH_OFFSET);
		//Note: Unlike C/C++, Java does not support explicit use of pointers! 
		// we have to make another copy of the data
		// This is not effecient in protocol implementation*/
		
		//set data length to the length the same as the one in the packet
		seg.data = new byte[seg.length - RDTSegment.HDR_SIZE];
		for (int i=0; (i< seg.length - RDTSegment.HDR_SIZE); i++)
		{
			seg.data[i] = payload[i + RDTSegment.HDR_SIZE];
		}
	}
		
	
} // end ReceiverThread class

