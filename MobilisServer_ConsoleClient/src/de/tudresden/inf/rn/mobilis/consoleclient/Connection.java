package de.tudresden.inf.rn.mobilis.consoleclient;

import de.tudresden.inf.rn.mobilis.consoleclient.helper.ConnectionStatus;
import de.tudresden.inf.rn.mobilis.xmpp.beans.XMPPBean;
import de.tudresden.inf.rn.mobilis.xmpp.beans.coordination.CreateNewServiceInstanceBean;
import de.tudresden.inf.rn.mobilis.xmpp.beans.coordination.MobilisServiceDiscoveryBean;
import de.tudresden.inf.rn.mobilis.xmpp.beans.coordination.SendNewServiceInstanceBean;
import de.tudresden.inf.rn.mobilis.xmpp.beans.coordination.StopServiceInstanceBean;
import de.tudresden.inf.rn.mobilis.xmpp.beans.deployment.*;
import de.tudresden.inf.rn.mobilis.xmpp.mxj.BeanProviderAdapter;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smackx.filetransfer.*;

import java.io.File;
import java.util.Date;
import java.util.Observable;

/**
 * The Class Connection.
 */
public class Connection extends Observable implements ConnectionListener {

	/** The _controller. */
	private Controller _controller;

	/** The _xmpp connection. */
	private XMPPConnection _xmppConnection;

	/** The _file transfer manager. */
	private FileTransferManager _fileTransferManager;

    /**
	 * Instantiates a new connection.
	 * 
	 * @param controller
	 *            the controller
	 */
	public Connection(Controller controller) {
		_controller = controller;
	}

	public XMPPConnection getXMPPConnection() {
		return _xmppConnection;
	}
	
	/**
	 * Converts an xmpp bean to iq.
	 * 
	 * @param bean
	 *            the bean to convert
	 * @return the iQ
	 */
	public IQ beanToIQ( final XMPPBean bean ) {
		return new IQ() {

			@Override
			public String getPacketID() {
				return bean.getId();
			}

			@Override
			public String getChildElementXML() {
				return bean.toXML();
			}
		};
	}

	/**
	 * Connect to xmpp server.
	 * 
	 * @return true, if successful
	 */
	public boolean connectToXMPPServer() {
		XMPPConnection.DEBUG_ENABLED = (new Settings()).isSmackDebugMode();

		ConnectionConfiguration connnectionConfig = new ConnectionConfiguration( _controller
				.getSettings().getXMPPServerAddress(), _controller.getSettings()
				.getXMPPServerPort() );

		_xmppConnection = new XMPPConnection( connnectionConfig );

		try {
			_xmppConnection.connect();

			_fileTransferManager = new FileTransferManager( _xmppConnection );
			FileTransferNegotiator.setServiceEnabled( _xmppConnection, true );

			initFileTransferManager();
		} catch ( XMPPException e ) {
			e.printStackTrace();

			return false;
		}

		return true;
	}

	/**
	 * Disconnect from xmpp.
	 */
	public void disconnect() {
		if ( isConnected() )
			_xmppConnection.disconnect();
	}

	/**
	 * Inits the file transfer manager.
	 */
	private void initFileTransferManager() {
		_fileTransferManager.addFileTransferListener( new FileTransferListener() {
			public void fileTransferRequest( FileTransferRequest request ) {
				File incomingFile = createNewIncomingFile( request.getFileName() );

				try {
					if ( request.getFileSize() > 0 ) {
						IncomingFileTransfer transfer = request.accept();

						transfer.recieveFile( incomingFile );
					}
				} catch ( XMPPException e ) {
					e.printStackTrace();
				}
			}
		} );
	}

	/**
	 * Creates a new incoming file.
	 * 
	 * @param fileName
	 *            the file name
	 * @return the file
	 */
	private File createNewIncomingFile( String fileName ) {
		/* The _upload folder. */
        String _uploadFolder = "upload";
        File inFile = new File(_uploadFolder, fileName );

		// If file already exists, create a new file with timestamp
		if ( inFile.exists() )
			inFile = new File(_uploadFolder, createNewFileName( fileName ) );

		return inFile;
	}

	/**
	 * Creates a new file name.
	 * 
	 * @param fileName
	 *            the file name
	 * @return the string
	 */
	private String createNewFileName( String fileName ) {
		StringBuilder sb = new StringBuilder();

		int pointIndex = fileName.lastIndexOf( "." );
		int strLength = fileName.length();

		sb.append( fileName.subSequence( 0, pointIndex ) ).append( "_" )
				.append( System.currentTimeMillis() )
				.append( fileName.substring( pointIndex, strLength ) );

		return sb.toString();
	}

	/**
	 * Checks if xmpp is connected.
	 * 
	 * @return true, if xmpp is connected
	 */
	public boolean isConnected() {
		return null != _xmppConnection && _xmppConnection.isConnected();
	}

	/**
	 * Login to xmpp.
	 * 
	 * @return true, if successful
	 */
	public boolean loginXMPP() {
		if ( _xmppConnection.isConnected() ) {
			try {
				_xmppConnection.login( _controller.getSettings().getClientNode(), _controller
						.getSettings().getClientPassword(), _controller.getSettings()
						.getClientResource() );
				_controller.getSettings().setXMPPServerDomain( _xmppConnection.getServiceName() );
				_controller.getSettings().setClientJid( _xmppConnection.getUser() );
			} catch ( XMPPException e ) {
				e.printStackTrace();

				return false;
			}
            setChanged();
            notifyObservers(ConnectionStatus.CONNECTED);
			return true;
		}

		return false;
	}

	/**
	 * Prints the xmpp and settings info.
	 */
	public void printXMPPInfo() {
		StringBuilder sb = new StringBuilder();
		sb.append( "/========== Status of Connection ==========\\" );

		if ( isConnected() ) {
			sb.append( "\nConnectionID: " ).append( _xmppConnection.getConnectionID() );
			sb.append( "\nHost: " ).append( _xmppConnection.getHost() );
			sb.append( "\nService Name: " ).append( _xmppConnection.getServiceName() );
			sb.append( "\nUser: " ).append( _xmppConnection.getUser() );

			sb.append( "\nRoster:" );
			for ( RosterEntry entry : _xmppConnection.getRoster().getEntries() ) {
				sb.append( entry.toString() ).append( "; " );
			}
		} else
			sb.append( "\nNot connected!" );

		sb.append( "\n\\==========================================/" );

		sb.append( "\n/========== Settings ======================\\\n" );

		sb.append( _controller.getSettings().toString() );

		sb.append( "\n\\==========================================/" );
	}

	/**
	 * Register extensions.
	 */
	private void registerExtensions() {
		( new BeanProviderAdapter( new CreateNewServiceInstanceBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new MobilisServiceDiscoveryBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new PrepareServiceUploadBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new ServiceUploadConclusionBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new RegisterServiceBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new InstallServiceBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new ConfigureServiceBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new UninstallServiceBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new UnregisterServiceBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new UpdateServiceBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new StopServiceInstanceBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new SendNewServiceInstanceBean() ) ).addToProviderManager();
		( new BeanProviderAdapter( new ExecuteSynchronizeRuntimesBean() ) ).addToProviderManager();
	}

	/**
	 * Send iq.
	 * 
	 * @param iq
	 *            the iq
	 */
	public void sendIQ( IQ iq ) {
		// _controller.getLog().writeToConsole("Send IQ: " + iq.toXML());

		_xmppConnection.sendPacket( iq );
	}

	/**
	 * Send service discovery.
	 * 
	 * @param namespace
	 *            the namespace of service
	 * @param version
	 *            the version of service
	 * @param requestMsdl
	 *            true, if msdl should be delivered by filetransfer
	 */
	public void sendServiceDiscovery( String namespace, int version, boolean requestMsdl ) {
		final MobilisServiceDiscoveryBean bean = new MobilisServiceDiscoveryBean();

		if ( null != namespace )
			bean.serviceNamespace = namespace;
		if ( version > 0 )
			bean.serviceVersion = version;

		bean.requestMSDL = requestMsdl;

		bean.setTo( _controller.getSettings().getMobilisCoordinatorJid() );
		bean.setType( XMPPBean.TYPE_GET );

		sendXMPPBean( bean );
	}

	/**
	 * Send create service.
	 * 
	 * @param serviceNamespace
	 *            the service namespace
	 * @param version
	 *            the version of service
	 */
	public void sendCreateService( String serviceNamespace, int version ) {
		
		final CreateNewServiceInstanceBean bean = new CreateNewServiceInstanceBean(
				serviceNamespace, null );
		bean.setAnswerID((new Date()).toString());
		if ( version > 0 ) {
			bean.serviceVersion = version;
		}

		bean.setTo( _controller.getSettings().getMobilisCoordinatorJid() );
		bean.setType( XMPPBean.TYPE_SET );

		sendXMPPBean( bean );
	}

	/**
	 * Send create service.
	 * 
	 * @param serviceNamespace
	 *            the service namespace
	 * @param minVersion
	 *            the min version of service
	 * @param maxVersion
	 *            the max version of service
	 */
	public void sendCreateService( String serviceNamespace, int minVersion, int maxVersion ) {
		final CreateNewServiceInstanceBean bean = new CreateNewServiceInstanceBean(
				serviceNamespace, null );
		bean.setAnswerID((new Date()).toString());
		if ( minVersion > 0 ) {
			bean.minVersion = minVersion;
		}

		if ( maxVersion > 0 ) {
			bean.maxVersion = maxVersion;
		}

		bean.setTo( _controller.getSettings().getMobilisCoordinatorJid() );
		bean.setType( XMPPBean.TYPE_SET );

		sendXMPPBean( bean );
	}
	
	/**
	 * Request for synchronize the remote runtime with other know runtimes
	 */
	public void sendExecuteSynchronizeRequest(){
		final ExecuteSynchronizeRuntimesBean bean = new ExecuteSynchronizeRuntimesBean();
		bean.setTo( _controller.getSettings().getMobilisRuntimeJid());
		bean.setType( XMPPBean.TYPE_SET );
		sendXMPPBean(bean);
	}

	/**
	 * Send xmpp bean.
	 * 
	 * @param bean
	 *            the bean to send
	 */
	public void sendXMPPBean( XMPPBean bean ) {
		IQ iq = beanToIQ( bean );
		iq.setTo( bean.getTo() );
		iq.setType( getTypeOfXMPPBean( bean ) );

		sendIQ( iq );
	}

	/**
	 * Transmit a file.
	 * 
	 * @param file
	 *            the file to transmit
	 * @param fileDesc
	 *            the file description
	 * @param toJid
	 *            the receiver jid of the file
	 * @return true, if successful
	 */
	public boolean transmitFile( File file, String fileDesc, String toJid ) {
		boolean transferSuccessful = false;
		OutgoingFileTransfer transfer = _fileTransferManager.createOutgoingFileTransfer( toJid );

		// check if file exists
		if ( file.exists() ) {
			try {
				// counter for sending tries
				int counter = 0;

				// start sending file
				transfer.sendFile( file, fileDesc );

				// while file is sending
				/* The _filetransfer timeout. */
                int _filetransferTimeout = 15;
                while ( !transfer.isDone() ) {
					// if counter of maximum tries has reached, cancel
					// transmission
					if ( counter == _filetransferTimeout) {
						// _controller.getLog().writeToConsole("ERROR: Filetransfer canceled. No Response!");
						break;
					}
					// increase try counter of sending tries
					counter++;

					// wait for 1000 ms and try sending the file again
					try {
						Thread.sleep( 1000 );
					} catch ( InterruptedException e1 ) {
                        e1.printStackTrace();
					}
				}

				transferSuccessful = transfer.isDone() && counter < _filetransferTimeout && transfer.getBytesSent() > -1;
			} catch ( XMPPException e ) {
				e.printStackTrace();
			}
		}

		return transferSuccessful;
	}

	/**
	 * Gets the type of xmpp bean.
	 * 
	 * @param bean
	 *            the bean
	 * @return the type of xmpp bean
	 */
	private Type getTypeOfXMPPBean( XMPPBean bean ) {
		Type type = Type.GET;

		switch ( bean.getType() ) {
		case XMPPBean.TYPE_ERROR:
			type = Type.ERROR;
			break;

		case XMPPBean.TYPE_RESULT:
			type = Type.RESULT;
			break;

		case XMPPBean.TYPE_SET:
			type = Type.SET;
			break;
		}

		return type;
	}

    @Override
    public void connectionClosed() {
        setChanged();
        notifyObservers(ConnectionStatus.DISCONNECTED);
    }

    @Override
    public void connectionClosedOnError(Exception e) {
        setChanged();
        notifyObservers(ConnectionStatus.DISCONNECTED);
        e.printStackTrace();
    }

    @Override
    public void reconnectingIn(int i) {
        setChanged();
        notifyObservers(ConnectionStatus.RECONNECTING);
    }

    @Override
    public void reconnectionSuccessful() {
        setChanged();
        notifyObservers(ConnectionStatus.CONNECTED);
    }

    @Override
    public void reconnectionFailed(Exception e) {
        setChanged();
        notifyObservers(ConnectionStatus.DISCONNECTED);
        e.printStackTrace();
    }
}
