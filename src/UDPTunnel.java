import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;


/**
 * 
 * @author jts
 *
 */
public class UDPTunnel {
	
	public static enum Type {
		Client,
		Server
	}
	
	public Type type;
	
	/**
	 * Wraps a TCPListenerThread and a UDPListenerThread on the same port
	 */
	public class TunnelThread {
		
		/**
		 * Listens on TCP and forwards data to UDP
		 */
		private class TCPListenerThread extends Thread {
			@Override
			public void run() {
				try {
					initTcpConnection();
					InputStream i = tcpConnection.getInputStream();
					for(;;) {
						try {
							// get the length of the UDP packet (how much of the TCP stream to read)
							int packetLen = i.read() << 24 | i.read() << 16 | i.read() << 8 | i.read();
							if(packetLen <= 0) {
								System.err.println(packetLen);
								System.err.println("End of stream on port " + port);
								initTcpConnection();
								continue;
							}
							System.out.println("Forwarding UDP packet from TCP (" + packetLen + " bytes)");
							byte[] buf = new byte[packetLen];
							i.read(buf);
							udpConnection.send(new DatagramPacket(buf, buf.length, InetAddress.getLocalHost(), ephemeralPort));
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		
		/**
		 * Listens on UDP and forwards data to TCP
		 */
		private class UDPListenerThread extends Thread {
			@Override
			public void run() {
				try {
					initTcpConnection();
					byte[] buf = new byte[64000];
					DatagramPacket p = new DatagramPacket(buf, buf.length);
					OutputStream o;
					o = tcpConnection.getOutputStream();
					for(;;) {
						try {
							udpConnection.receive(p);
							ephemeralPort = p.getPort();
							// write the length of the packet
							int packetLen = p.getLength();
							System.out.println("Forwarding TCP packet from UDP (" + packetLen + " bytes)");
							o.write(packetLen >> 24);
							o.write(packetLen >> 16);
							o.write(packetLen >> 8);
							o.write(packetLen);
							// write the packet
							o.write(p.getData(), p.getOffset(), packetLen);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		
		public final int port;
		public int ephemeralPort;
		public final TCPListenerThread tcp;
		public final UDPListenerThread udp;
		public Socket tcpConnection;
		public final DatagramSocket udpConnection;
		
		public synchronized void initTcpConnection() throws IOException {
			if(this.tcpConnection != null && this.tcpConnection.isConnected() && !this.tcpConnection.isClosed())
				return;
			if(type == Type.Client) {
				this.tcpConnection = new Socket(InetAddress.getLocalHost(), port);
			} else {
				ServerSocket ss = new ServerSocket(port);
				ss.setSoTimeout(10000);
				this.tcpConnection = ss.accept();
				ss.close();
			}
		}
		
		public TunnelThread(int port) throws IOException {
			this.port = port;
			this.ephemeralPort = port;
			System.out.println("Tunnelling UDP port " + port);
			if(type == Type.Client) {
				this.udpConnection = new DatagramSocket(port);
			} else {
				this.udpConnection = new DatagramSocket();
			}
			this.tcp = new TCPListenerThread();
			this.udp = new UDPListenerThread();
		}
		
		/**
		 * Starts both threads
		 */
		public void start() {
			this.tcp.start();
			this.udp.start();
		}
	}
	
	public static void showUsage() {
		System.err.println("Tunnels UDP packets through a SSH tunnel");
		System.err.println("Usage: UDPTunnel <type> <port1> [port2] [port3] [...]");
		System.err.println("\ttype: either \"server\" or \"client\"");
		System.exit(1);
	}
	
	public UDPTunnel(String[] args) throws IOException {
		if(args.length <= 1) {
			showUsage();
		}
		String type = args[0].trim().toLowerCase();
		if(type.equals("client")) {
			this.type = Type.Client;
		} else if(type.equals("server")) {
			this.type = Type.Server;
		} else {
			showUsage();
		}
		System.out.println(this.type);
		// start the tunnel threads
		for(int i = 1; i < args.length; i++) {
			String s = args[i];
			int port = Integer.parseInt(s);
			if(port > 0 && port < 0xFFFF) {
				System.out.println(port);
				new TunnelThread(port).start();
			}
		}
	}
	
	/**
	 *	@param args type (client/server), and list of ports to tunnel
	 */
	public static void main(String[] args) throws IOException {
		new UDPTunnel(args);
	}
}
