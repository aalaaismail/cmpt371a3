/**
 * @author mohamed
 *
 */


import java.io.*;
import java.net.*;
import java.util.*;

public class TestClient {

	/**
	 * 
	 */
	public TestClient() {
		
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		 if (args.length != 3) {
	         System.out.println("Required arguments: dst_hostname dst_port local_port");
	         return;
	      }
		 String hostname = args[0]; //dnsnamelook up to get ip
	     int dst_port = Integer.parseInt(args[1]);
	     int local_port = Integer.parseInt(args[2]);
	     	      
	     RDT rdt = new RDT(hostname, dst_port, local_port, 5, 5, 1);
	     RDT.setLossRate(0.2);
	  
	     byte[] buf = new byte[RDT.MSS];  //buffer set to MSS
	     byte[] data = new byte[321]; //data
	     
	     System.out.println("\n\n ======>CLIENT IS SENDING DATA<========\n\n " );
	     for (int i=0; i<data.length; i++)
	    	 data[i] = 0;
	     rdt.send(data, data.length);
	     
	     for (int i=0; i<data.length; i++)
	    	 data[i] = 1;
	     rdt.send(data, data.length);
	     
	     for (int i=0; i<data.length; i++)
	    	 data[i] = 2;
	     rdt.send(data, data.length);
	     
	     for (int i=0; i<data.length; i++)
	    	 data[i] = 3;
	     rdt.send(data, data.length);

	     for (int i=0; i<data.length; i++)
	    	 data[i] = 4;
	     rdt.send(data, data.length);
	 
	     
	     System.out.println(System.currentTimeMillis() + "\n\nCLIENT HAS SENT ALL DATA \n\n" );
	     System.out.flush();
	     
	     //rdt.receive(buf, RDT.MSS);
	     rdt.close();
	     System.out.println("Client is done " );
	}

}
