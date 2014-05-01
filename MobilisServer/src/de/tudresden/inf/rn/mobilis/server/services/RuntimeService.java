package de.tudresden.inf.rn.mobilis.server.services;

import de.tudresden.inf.rn.mobilis.deployment.upload.FileHelper;
import de.tudresden.inf.rn.mobilis.deployment.upload.FileUploadInformation;
import de.tudresden.inf.rn.mobilis.server.MobilisManager;
import de.tudresden.inf.rn.mobilis.server.agents.MobilisAgent;
import de.tudresden.inf.rn.mobilis.server.deployment.container.ServiceContainer;
import de.tudresden.inf.rn.mobilis.server.deployment.exception.InstallServiceException;
import de.tudresden.inf.rn.mobilis.xmpp.beans.XMPPBean;
import de.tudresden.inf.rn.mobilis.xmpp.beans.deployment.ExecuteSynchronizeRuntimesBean;
import de.tudresden.inf.rn.mobilis.xmpp.beans.deployment.PrepareServiceUploadBean;
import de.tudresden.inf.rn.mobilis.xmpp.beans.deployment.ServiceUploadConclusionBean;
import de.tudresden.inf.rn.mobilis.xmpp.beans.runtime.SynchronizeRuntimesBean;
import de.tudresden.inf.rn.mobilis.xmpp.mxj.BeanHelper;
import de.tudresden.inf.rn.mobilis.xmpp.mxj.BeanIQAdapter;
import de.tudresden.inf.rn.mobilis.xmpp.mxj.BeanProviderAdapter;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.Roster.SubscriptionMode;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.PacketExtension;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.jivesoftware.smackx.entitycaps.EntityCapsManager;
import org.jivesoftware.smackx.filetransfer.*;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Level;

/**
 * The Class RuntimeService for uploading new mobilis services as jar files.
 */
public class RuntimeService extends MobilisService {

	// private DateFormat _dateFormatter = null;
	/** The manager for file transfers. */
	private FileTransferManager _fileTransferManager = null;

	/** The default folder for uploaded jar files. */
	private String _uploadServiceFolder = "service";

	/**
	 * The list with expected jar files (a PrepareServiceUploadBean is required
	 * first).
	 */
	private Map< String, FileUploadInformation> _expectedUploads = Collections
			.synchronizedMap( new HashMap< String, FileUploadInformation >() );

	

	// public void log( String str ) {
	// System.out.println( "[" + _dateFormatter.format(
	// System.currentTimeMillis() ) + "] " + str );
	// }

	/**
	 * Creates a new incoming file. If a file of the same name already exists, a
	 * timestamp will be added at the end of the filename.
	 * 
	 * @param fileName
	 *            the file name for the incoming file
	 * @return the file which was stored
	 */
	private File createNewIncomingFile( String fileName ) {
		File inFile = new File( _uploadServiceFolder, fileName );

		// If file already exists, create a new file with timestamp
		if ( inFile.exists() )
			inFile = new File( _uploadServiceFolder, createNewFileName( fileName ) );

		return inFile;
	}

	/**
	 * Creates a new file name. A timestamp will be added to filename if it
	 * already exist.
	 * 
	 * @param fileName
	 *            the name of the file
	 * @return the new filename
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
	 * Inits the file transfer manager.
	 */
	private void initFileTransferManager() {
		_fileTransferManager = new FileTransferManager( getAgent().getConnection() );
		FileTransferNegotiator.setServiceEnabled( getAgent().getConnection(), true );

		_fileTransferManager.addFileTransferListener( new FileTransferListener() {
			public void fileTransferRequest(final FileTransferRequest request ) {
				Thread t = new Thread(new Runnable() {
					
					@Override
					public void run() {
						String message = "";
						boolean transmissionSuccessful = false;
						File incomingFile = createNewIncomingFile( request.getFileName() );
						
						MobilisManager.getLogger().log(
								Level.INFO,
								String.format( "Incoming FileTransfer from: %s with filename: %s",
										request.getRequestor(), request.getFileName() ) );
						
						// log( "file expected: requestor="
						// + _expectedUploads.containsKey( request.getRequestor() ) +
						// " filename="
						// + _expectedUploads.get( request.getRequestor() ) );
						
						// Only if 'preparefile' bean was sent, requestor can upload the
						// service
						FileUploadInformation inf = _expectedUploads.get( request.getRequestor() );
						if ( _expectedUploads.containsKey( request.getRequestor() )
								&& request.getFileName().equals(
										inf.fileName )
								&& null != incomingFile ) {
							// Accept Filetransfer
							try {
								if ( request.getFileSize() > 0 ) {
									IncomingFileTransfer transfer = request.accept();
									InputStream recieveFileInputStream = transfer.recieveFile();
									FileHelper.createFileFromInputStream(recieveFileInputStream, incomingFile.getAbsolutePath());
									
		//							if (transfer.getStatus().equals(FileTransfer.Status.complete)) {
										transmissionSuccessful = true;
										message = String.format( "Successful FileTransfer of file: %s",
												incomingFile.getName() );
										MobilisManager.getLogger().log(
												Level.INFO,
												message );
		//							} else {
		//								message = String.format( "FileTransfer of file: %s failed: ",
		//										incomingFile.getName()); 
		//							}
		
								}
							} catch ( XMPPException e ) {
								transmissionSuccessful = false;
								message = String.format( "FileTransfer of file: %s failed: ",
										incomingFile.getName(), e.getMessage() );
							}
						} else {
							message = "File was not expected.";
		
							request.reject();
						}
		
						if ( transmissionSuccessful ) {
							synchronized ( _expectedUploads ) {
								_expectedUploads.remove( request.getRequestor() );
							}
							//get servicename from serviceContainer for creating Rostergroup
							ServiceContainer serviceContainer = new ServiceContainer( incomingFile );
							try {
								serviceContainer.extractServiceContainerConfig();
							} catch (InstallServiceException e) {
							}
							Roster runtimeRoster = MobilisManager.getInstance().getRuntimeRoster();
							RosterGroup rg = runtimeRoster.getGroup(MobilisManager.securityUserGroup + serviceContainer.getServiceName()+serviceContainer.getServiceVersion());
							String jid = StringUtils.parseBareAddress(request.getRequestor());
							Date date = new Date();
							//if roustergroup for service doesn't exist installing new service is allowed for all deploy users
							if(rg==null){
								message += MobilisManager.getInstance().installAndConfigureAndRegisterServiceFromFile(
										incomingFile, inf.autoDeploy, inf.singleMode, "runtime", null, null, false, date);
								//create RosterGroup with GroupName=(serviceName+serviceVersion) as Security User Group and add uploading User to group
								rg = runtimeRoster.createGroup(MobilisManager.securityUserGroup + serviceContainer.getServiceName()+serviceContainer.getServiceVersion());
								try {
									rg.addEntry(runtimeRoster.getEntry(jid));
								} catch (XMPPException e) {
									System.out.println("Couldn't add user to Rostergroup. Reason: " + e.getMessage());
									e.printStackTrace();
								}
								sendSynchronizeRuntimesBeanSET(MobilisManager.getInstance().getNewServiceJIDByDate(date));
							}
							else{
								//if rostergroup for service exist, check if request user is in that group. if not, he isn't authorized upload and change service
								if(rg.getEntry(jid)==null){
									message += "\n[Service not installed]: User " + jid + " is not authorized to change the already installed service " + serviceContainer.getServiceName() + " version " + serviceContainer.getServiceVersion();
								}
								else{
									message += MobilisManager.getInstance().installAndConfigureAndRegisterServiceFromFile(
											incomingFile, inf.autoDeploy, inf.singleMode, "runtime", null, null, false, null);
								}
							}
							
							
						} else if ( message.equals("") || message == null ) {
							message = "Unknown failure while uploading file";
						}
		
						if ( null != message && !message.equals("") ) {
							MobilisManager.getLogger().log( Level.INFO, message );
						}
		
						sendServiceUploadConclusionBeanSET( request.getRequestor(), transmissionSuccessful,
								incomingFile.getName(), message );
					}

				});
				t.start();
			}
		} );
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see de.tudresden.inf.rn.mobilis.server.services.MobilisService#
	 * registerPacketListener()
	 */
	@Override
	protected void registerPacketListener() {
		XMPPBean prepareServiceUploadBean = new PrepareServiceUploadBean();
		XMPPBean serviceUploadConclusionBean = new ServiceUploadConclusionBean();
		XMPPBean publishNewServiceBean = new SynchronizeRuntimesBean();
		XMPPBean executeSynchronizeBean = new ExecuteSynchronizeRuntimesBean();
		( new BeanProviderAdapter( prepareServiceUploadBean ) ).addToProviderManager();
		( new BeanProviderAdapter( serviceUploadConclusionBean ) ).addToProviderManager();
		( new BeanProviderAdapter( publishNewServiceBean ) ).addToProviderManager();
		( new BeanProviderAdapter( executeSynchronizeBean ) ).addToProviderManager();
		IQListener iqListener = new IQListener();
		PacketTypeFilter locFil = new PacketTypeFilter( IQ.class );
		getAgent().getConnection().addPacketListener( iqListener, locFil );
	}

	/**
	 * Sends a service upload conclusion bean.
	 * 
	 * @param requestor
	 *            the requestor which uploaded the jar file
	 * @param transmissionSuccessful
	 *            true if transmission was successful
	 * @param filename
	 *            the name of the stored file
	 * @param message
	 *            an optional message
	 */
	private void sendServiceUploadConclusionBeanSET( String requestor,
			boolean transmissionSuccessful, String filename, String message ) {
		ServiceUploadConclusionBean bean = new ServiceUploadConclusionBean( transmissionSuccessful,
				filename, message );
		bean.setTo( requestor );
		bean.setType( XMPPBean.TYPE_SET );

		getAgent().getConnection().sendPacket( new BeanIQAdapter( bean ) );
	}
	
	/**
	 * Sends the newServiceJID to another Runtime for adding it to their ServiceDiscovery Roster
	 * @param newServiceJID
	 */
	private void sendSynchronizeRuntimesBeanSET(String newServiceJID){

		Roster runtimeRoster = MobilisManager.getInstance().getRuntimeRoster();
		RosterGroup rg = runtimeRoster.getGroup(MobilisManager.securityRuntimeGroup +"runtimes");
		if(rg!=null){
			for(RosterEntry entry : rg.getEntries()){
				
				//send SET request of new Services just to online runtimes
				for ( Iterator<Presence> iter = runtimeRoster.getPresences(entry.getUser()); iter.hasNext(); )
				{
					Presence presence = iter.next();
					String presenceRessource = StringUtils.parseResource((presence.getFrom()));
					
					if(presence.isAvailable() && presenceRessource.equalsIgnoreCase("runtime")){
						List<String> newServiceJIDs = new ArrayList<String>();
						newServiceJIDs.add(newServiceJID);
						SynchronizeRuntimesBean bean = new SynchronizeRuntimesBean(newServiceJIDs);
						String recipientJIDOfRuntime = entry.getUser() + "/Runtime";
						bean.setTo(recipientJIDOfRuntime);
						bean.setType(XMPPBean.TYPE_SET);
						getAgent().getConnection().sendPacket( new BeanIQAdapter( bean ) );
					}
				}
			}
		}
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * de.tudresden.inf.rn.mobilis.server.services.MobilisService#shutdown()
	 */
	@Override
	public void shutdown() throws Exception {
		_fileTransferManager = null;

		super.shutdown();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * de.tudresden.inf.rn.mobilis.server.services.MobilisService#startup(de
	 * .tudresden.inf.rn.mobilis.server.agents.MobilisAgent)
	 */
	@Override
	public void startup( MobilisAgent agent ) throws Exception {
		super.startup( agent );
		getRoster(agent);
		// _dateFormatter = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss:SSS" );

		checkServiceUploadFolder();
		initFileTransferManager();
	};

	/**
	 * Checks service upload folder and create it if necessary.
	 */
	private void checkServiceUploadFolder() {
		File uploadFolder = new File( _uploadServiceFolder );

		if ( !uploadFolder.exists() )
			uploadFolder.mkdir();
	}

	/**
	 * The listener interface for receiving IQ events. The class that is
	 * interested in processing a IQ event implements this interface, and the
	 * object created with that class is registered with a component using the
	 * component's <code>addIQListener<code> method. When
	 * the IQ event occurs, that object's appropriate
	 * method is invoked.

	 */
	private class IQListener implements PacketListener {

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * org.jivesoftware.smack.PacketListener#processPacket(org.jivesoftware
		 * .smack.packet.Packet)
		 */
		@Override
		public void processPacket( Packet packet ) {
			if ( packet instanceof BeanIQAdapter ) {
				XMPPBean inBean = ( (BeanIQAdapter)packet ).getBean();
				
				if ( inBean instanceof PrepareServiceUploadBean ) {
					handlePrepareServiceUploadBean( (PrepareServiceUploadBean)inBean );
				} else if ( inBean instanceof ServiceUploadConclusionBean
						&& inBean.getType() == XMPPBean.TYPE_RESULT ) {
					// Do nothing, just ack
				} else if ( (inBean instanceof ExecuteSynchronizeRuntimesBean) && (inBean.getType() == XMPPBean.TYPE_SET)){
					handleExecuteSynchronizeRequest((ExecuteSynchronizeRuntimesBean) inBean);
				} else if ( inBean instanceof SynchronizeRuntimesBean){
						SynchronizeRuntimesBean bean = (SynchronizeRuntimesBean) inBean;
						if(inBean.getType() == XMPPBean.TYPE_SET )
							handleSynchronizeRuntimesBean(bean);
						if(inBean.getType() == XMPPBean.TYPE_ERROR) {
							synchronizeRuntimesErrorBean(bean);
						} else {
							if(inBean.getType() == XMPPBean.TYPE_RESULT){
								if((bean.newServiceJIDs == null) || (bean.newServiceJIDs.size() == 0)){
									//no services result bean for GET REQUEST or result bean for SET Request.
								} else {
									handleSynchronizeRuntimesBeanResult(bean);
								}
							}
							if(inBean.getType() == XMPPBean.TYPE_GET)
								handleSynchronizeRuntimesGET(bean);
						}
				} else {
					handleUnknownBean( inBean );
				}
			}
		}
		
		/**
		 * handles a request from a remote client user with administration rights on the server to manually synchronize the 
		 * remote services with other runtimes
		 * @param inBean
		 */
		private void handleExecuteSynchronizeRequest(
				ExecuteSynchronizeRuntimesBean inBean) {
			XMPPBean answerBean = null;
			Roster runtimeRoster = MobilisManager.getInstance().getRuntimeRoster();
			RosterGroup admins = runtimeRoster.getGroup(MobilisManager.securityUserGroup + "administrators");
			if(admins != null){
				if(admins.contains(StringUtils.parseBareAddress(inBean.getFrom()))){
					updateRemoteServices();
					answerBean = BeanHelper
							.CreateResultBean( inBean, new ExecuteSynchronizeRuntimesBean() );
				} else {
					//send error bean
					answerBean = BeanHelper.CreateErrorBean( inBean, "modify", "access-denied",
							( "Access denied: You need Administrator rights to perform this action "));
				}
			} else {
				//send error bean
				answerBean = BeanHelper.CreateErrorBean( inBean, "modify", "access-denied",
						( "Access denied: You need Administrator rights to perform this action "));
			}
			
			getAgent().getConnection().sendPacket( new BeanIQAdapter( answerBean ) );
			
		}

		/**
		 * Process a Result of a GET Request.
		 * @param inBean
		 */
		private void handleSynchronizeRuntimesBeanResult(SynchronizeRuntimesBean inBean) {
			addRemoteServiceJIDsToRoster(inBean.getNewServiceJIDs());
		}

		/**
		 * Process a GET Request for local services and push them to the requestor.
		 * @param inBean
		 */
		private void handleSynchronizeRuntimesGET(SynchronizeRuntimesBean inBean) {
			XMPPBean answerBean = null;
			Roster roster = MobilisManager.getInstance().getRuntimeRoster();
			RosterGroup allowedRuntimes = roster.getGroup(MobilisManager.securityRuntimeGroup + "runtimes");

			//first security check if requesting runtime is in the runtimes rostergroup
			if(allowedRuntimes !=null){
				if(allowedRuntimes.getEntry(StringUtils.parseBareAddress(inBean.getFrom()))!=null){
					// runtime allowed
					addRemoteServiceJIDsToRoster(inBean.getNewServiceJIDs());
					answerBean = BeanHelper
							.CreateResultBean( inBean, new SynchronizeRuntimesBean(getLocalServiceJIDs()) );
				}	else {
					answerBean = BeanHelper.CreateErrorBean( inBean, "modify", "access-denied",
							( "Access denied: Runtime is not allowed to request service Informations from " + inBean.getTo()));
				}
			} else {
				answerBean = BeanHelper.CreateErrorBean( inBean, "modify", "access-denied",
						( "Access denied: Runtime is not allowed to request service Informations from " + inBean.getTo()));
			}
			
			getAgent().getConnection().sendPacket( new BeanIQAdapter( answerBean ) );
		}


		/**
		 * Handle incoming SET Request to add new ServiceJID to the Roster
		 * @param inBean
		 */
		private void handleSynchronizeRuntimesBean(SynchronizeRuntimesBean inBean) {
			Roster runtimeRoster = MobilisManager.getInstance().getRuntimeRoster();
			RosterGroup rg = runtimeRoster.getGroup(MobilisManager.securityRuntimeGroup + "runtimes");
			XMPPBean outBean = null;
			if(rg.getEntry(StringUtils.parseBareAddress(inBean.getFrom()))!=null){
				//if requestor is an accepted runtime, add new service JIDs to rostergroup services
				String[] groups = {MobilisManager.remoteServiceGroup + "services"};
				try {
					for(String jid : inBean.getNewServiceJIDs()){
					runtimeRoster.createEntry(jid, jid, groups);
					}
					outBean = BeanHelper
							.CreateResultBean( inBean, new SynchronizeRuntimesBean() );
				} catch (XMPPException e) {
					System.out.println("Error while adding new Service JID to lokal Roster. Reason: " + e.getMessage());
					outBean = BeanHelper.CreateErrorBean( inBean, "modify", "unexpected-error",
							( "Could not add Service JIDs: " + (inBean.getNewServiceJIDs()).toString() + " to Roster of Runtime " + inBean.getTo() + ". Reason: " + e.getMessage()));
				}
			} else {
				outBean = BeanHelper.CreateErrorBean( inBean, "modify", "not-acceptable",
						(StringUtils.parseBareAddress(inBean.getFrom()) + " is not an authorized Runtime of " + inBean.getTo() + ". Publish new service denied.") );
			}
			
			getAgent().getConnection().sendPacket( new BeanIQAdapter( outBean ) );
		}
		
		/**
		 * Handle an occouring Error while Publishing a new Service
		 * @param inBean
		 */
		private void synchronizeRuntimesErrorBean(
				SynchronizeRuntimesBean inBean) {
			System.out.println("Error while trying to publish a new Service to Runtime " + inBean.getFrom() + ". Reason: " + inBean.errorText );
			System.out.println("Dienst JID: " + (inBean.getNewServiceJIDs()).toString());
		}
		
		/**
		 * Handle unknown bean.
		 * 
		 * @param inBean
		 *            the unknown bean
		 */
		private void handleUnknownBean( XMPPBean inBean ) {
			getAgent().getConnection().sendPacket(
					new BeanIQAdapter( BeanHelper.CreateErrorBean( inBean, "wait",
							"unexpected-request", "This request is not supported" ) ) );
		}

		/**
		 * Handle prepare service upload bean.
		 * 
		 * @param inBean
		 *            the bean
		 */
		private void handlePrepareServiceUploadBean( PrepareServiceUploadBean inBean ) {
			XMPPBean outBean = null;
			
			MobilisManager.getInstance();
			// If user is in the deploy security rostergroup of the runtime, proceed. Else send not authorized error.
			if(MobilisManager.getInstance().getRuntimeRoster().getGroup(MobilisManager.securityUserGroup +"deploy").contains(inBean.getFrom())){
				// if no name was set, respond an error
				if ( null == inBean.Filename || inBean.Filename.length() < 1 ) {
					outBean = BeanHelper.CreateErrorBean( inBean, "modify", "not-acceptable",
							"File name is null or empty." );
				} else {
					// store information in expected upload collection
					synchronized ( _expectedUploads ) {
						_expectedUploads.put( inBean.getFrom(), new FileUploadInformation(inBean.Filename, inBean.autoDeploy, inBean.singleMode) );
					}

					outBean = BeanHelper
							.CreateResultBean( inBean, new PrepareServiceUploadBean( true ) );
				}
			}
			else{
				outBean = BeanHelper.CreateErrorBean( inBean, "modify", "not-acceptable",
						("User " + StringUtils.parseBareAddress(inBean.getFrom()) + " is not authorized to upload files to runtime " + inBean.getTo() + "!") );
			}
			

			getAgent().getConnection().sendPacket( new BeanIQAdapter( outBean ) );
		}

	}

	@Override
	public List<PacketExtension> getNodePacketExtensions() {
		// TODO Auto-generated method stub
		return null;
	}
	
	private void getRoster(MobilisAgent agent){
		
		Connection connection = agent.getConnection();

		EntityCapsManager capsManager = EntityCapsManager.getInstanceFor(connection);
		capsManager.updateLocalEntityCaps();
		ServiceDiscoveryManager sdm = ServiceDiscoveryManager.getInstanceFor(connection);
		sdm.setEntityCapsManager(capsManager);
		MobilisManager.getInstance().setServiceDiscoveryManager(sdm);
		
		Roster runtimeRoster = connection.getRoster();
		runtimeRoster.setSubscriptionMode(SubscriptionMode.accept_all);
		MobilisManager.getInstance().setRuntimeRoster(runtimeRoster);
		MobilisManager.getInstance().setCapsManager(capsManager);
		//if not already existing, generate standard security group for uploading files
		if(runtimeRoster.getGroup(MobilisManager.securityUserGroup + "deploy")==null){
			runtimeRoster.createGroup(MobilisManager.securityUserGroup + "deploy");
		}
		
		//MobilisManager.getInstance().clearRemoteServicesRosterGroup();
		//updateRemoteServices();
		Timer timer = new Timer();

	    // delayed update of remote services on server start up
	    timer.schedule( new UpdateRemoteServices(), 1000 );


	}
	/**
	 * Delayed Request for synchronizing runtimes
	 * @author Philipp
	 *
	 */
	private class UpdateRemoteServices extends TimerTask{

		@Override
		public void run() {
			updateRemoteServices();
		}
		
	}
	
	private void updateRemoteServices(){
		Roster runtimeRoster = MobilisManager.getInstance().getRuntimeRoster();
		RosterGroup rg = runtimeRoster.getGroup(MobilisManager.securityRuntimeGroup + "runtimes");
		if(rg != null){
			for(RosterEntry entry : rg.getEntries()){
				
				//request new Services just from online runtimes
				for ( Iterator<Presence> iter = runtimeRoster.getPresences(entry.getUser()); iter.hasNext(); )
				{
					Presence presence = iter.next();
					String presenceRessource = StringUtils.parseResource((presence.getFrom()));
					
					if(presence.isAvailable() && presenceRessource.equalsIgnoreCase("runtime")){
						
						SynchronizeRuntimesBean outBean = new SynchronizeRuntimesBean(entry.getUser() + "/Runtime");
						outBean.setNewServiceJID(getLocalServiceJIDs());
						getAgent().getConnection().sendPacket( new BeanIQAdapter( outBean ) );
					}
				}
			}
		}
		
	}
	
	private List<String> getLocalServiceJIDs(){
		Roster roster = MobilisManager.getInstance().getRuntimeRoster();

		ArrayList<String> serviceJIDs = new ArrayList<String>();
		
		RosterGroup services = roster.getGroup(MobilisManager.remoteServiceGroup + "local-services");
		if(services!=null){
			for(RosterEntry entry : services.getEntries()){
				serviceJIDs.add(entry.getUser());
			}
		}
		
		return serviceJIDs;
	}
	
	/**
	 * adding a list of services running on a remote runtime to local runtimes roster and rostergroup
	 * @param serviceJIDs
	 */
	private void addRemoteServiceJIDsToRoster(List<String> serviceJIDs){
		Roster roster = MobilisManager.getInstance().getRuntimeRoster();
		String[] groups = {MobilisManager.remoteServiceGroup + "services"};
		try {
			for(String jid : serviceJIDs){
			roster.createEntry(jid, jid, groups);
			}
		}  catch (XMPPException e){
			MobilisManager.getLogger().severe("JID could not added cause: " + e.getMessage());
		}
	}
}
