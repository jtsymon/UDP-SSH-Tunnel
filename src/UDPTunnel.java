import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;


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
	public class TunnelThread extends Thread {
		
		// list of sockets for server mode
		private HashMap<InetAddress, Socket> peers = null;
		// single socket for client mode
		private Socket ClientTcpSocket = null;
		
		/**
		 * Listens on TCP and forwards data to UDP
		 */
		private class TCPListenerThread extends Thread {
			
			public Socket tcpSock;
			
			public TCPListenerThread(Socket s) {
				tcpSock = s;
			}
			
			@Override
			public void run() {
				try {
					InputStream i = tcpSock.getInputStream();
					for(;;) {
						try {
							// get the length of the UDP packet (how much of the TCP stream to read)
							int packetLen = i.read() << 24 | i.read() << 16 | i.read() << 8 | i.read();
							if(packetLen <= 0) {
								System.err.println("End of stream on port " + port);
								break;
							}
							if(packetLen >= 0xFFFF) {
								System.err.println("Invalid packet on port " + port);
								break;
							}
							if(type == Type.Server) {
								System.out.println("Forwarding packet from TCP port " + tcpSock.getPort() + " to UDP port " + port + " at host " + tcpSock.getInetAddress().getHostAddress() + " (" + packetLen + " bytes)");
							} else {
								System.out.println("Forwarding packet from TCP port " + tcpSock.getPort() + " to UDP port " + port + " (" + packetLen + " bytes)");
							}
							byte[] buf = new byte[packetLen];
							i.read(buf);
							udpSock.send(new DatagramPacket(buf, buf.length, InetAddress.getLocalHost(), ephemeralPort));
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					if(type == Type.Server) {
						synchronized (peers) {
							peers.remove(tcpSock.getInetAddress());
						}
					} else {
						synchronized (ClientTcpSocket) {
							ClientTcpSocket.close();
							ClientTcpSocket = null;
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
					byte[] buf = new byte[0xFFFF];
					DatagramPacket p = new DatagramPacket(buf, buf.length);
					OutputStream o;
					for(;;) {
						try {
							udpSock.receive(p);
							ephemeralPort = p.getPort();
							// get the tcp output stream
							if(type == Type.Server) {
								Socket tcpSock = peers.get(p.getAddress());
								if(tcpSock == null) {
									continue;
								}
								o = tcpSock.getOutputStream();
							} else {
								if(ClientTcpSocket == null) {
									break;
								}
								o = ClientTcpSocket.getOutputStream();
							}
							// write the length of the packet
							int packetLen = p.getLength();
							if(type == Type.Server) {
								System.out.println("Forwarding packet from UDP port " + p.getPort() + " to TCP port " + port + " at host " + p.getAddress().getHostAddress() + " (" + packetLen + " bytes)");
							} else {
								System.out.println("Forwarding packet from UDP port " + p.getPort() + " to TCP port " + port + " (" + packetLen + " bytes)");
							}
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
		public DatagramSocket udpSock;
		
		public TunnelThread(int port) throws IOException {
			this.port = port;
			this.ephemeralPort = port;
			System.out.println("Tunnelling UDP port " + port);
		}
		
		@Override
		public void run() {
			if(type == Type.Server) {
				try(ServerSocket ss = new ServerSocket(port)) {
					this.udpSock = new DatagramSocket();
					this.peers = new HashMap<InetAddress, Socket>();
					for(;;) {
						Socket s = ss.accept();
						synchronized (peers) {
							this.peers.put(s.getInetAddress(), s);
						}
						new TCPListenerThread(s).start();;
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else {
				try {
					this.udpSock = new DatagramSocket(port);
					Socket s = new Socket(InetAddress.getLocalHost(), port);
					ClientTcpSocket = s;
					new TCPListenerThread(s).start();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			new UDPListenerThread().start();;
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
