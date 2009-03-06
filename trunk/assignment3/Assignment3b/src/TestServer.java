/**
 * @author mohamed
 *
 */


import java.io.*;
import java.net.*;
import java.util.*;

public class TestServer {

	public TestServer() {
		
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		 if (args.length != 3) 
		 {
	         System.out.println("Required arguments: dst_hostname dst_port local_port");
	         return;
	     }
		 String hostname = args[0];
	     int dst_port = Integer.parseInt(args[1]);
	     int local_port = Integer.parseInt(args[2]);
	     	      
	     RDT rdt = new RDT(hostname, dst_port, local_port, 5, 5, 1);
	     RDT.setLossRate(0.2);
	     byte[] buf = new byte[RDT.MSS];  	     
	     System.out.println("\n\n ======>SERVER IS WAITING TO RECEIVE<========\n\n " );
	
	     
	     while (true) {
	    	 int size = rdt.receive(buf, RDT.MSS);
	    	 if(size == -1){
	    		 System.out.println("SIZE = -1");
	    		 break;
	    	 }
	    	// System.out.println("size is " + size);
	    	 for (int i=0; i<size; i++)
	    		 System.out.print(buf[i]);
	    	 System.out.println(" ");
	    	 System.out.flush();
	     } 
	     rdt.close();
	     System.out.println("Server done");
	}
}
