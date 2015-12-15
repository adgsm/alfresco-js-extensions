package rs.make.alfresco.jszip;

import java.lang.System;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.model.ContentModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;

import org.apache.log4j.Logger;
import org.mozilla.javascript.Function;

import rs.make.alfresco.alfcontent.AlfContent;
import rs.make.alfresco.callback.Callback;

/**
 * @desc expose zip functionality to Alf JS
 * 
 * @filename JSZip.java
 * @author Momcilo Dzunic (momcilo@dzunic.net)
 * @licence MIT
 */
public class JSZip extends Callback {
    protected TransactionService transactionService;
    public TransactionService getTransactionService() {
        return transactionService;
    }
    public void setTransactionService( TransactionService transactionService ) {
        this.transactionService = transactionService;
    }

    protected ServiceRegistry serviceRegistry;
    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }
    public void setServiceRegistry( ServiceRegistry serviceRegistry ) {
        this.serviceRegistry = serviceRegistry;
    }

    protected NodeService nodeService;
    public NodeService getNodeService() {
        return nodeService;
    }
    public void setNodeService( NodeService nodeService ) {
        this.nodeService = nodeService;
    }

    protected ContentService contentService;
    public ContentService getContentService() {
        return contentService;
    }
    public void setContentService( ContentService contentService ) {
        this.contentService = contentService;
    }

    protected PermissionService permissionService;
    public PermissionService getPermissionService() {
        return permissionService;
    }
    public void setPermissionService( PermissionService permissionService ) {
        this.permissionService = permissionService;
    }

    protected FileFolderService fileFolderService;
    public FileFolderService getFileFolderService() {
        return fileFolderService;
    }
    public void setFileFolderService( FileFolderService fileFolderService ) {
        this.fileFolderService = fileFolderService;
    }

    protected AlfContent alfContent;
    public AlfContent getAlfContent() {
        return alfContent;
    }
    public void setAlfContent( AlfContent alfContent ) {
        this.alfContent = alfContent;
    }

    protected Callback callback;
    public Callback getCallback() {
        return callback;
    }
    public void setCallback( Callback callback ) {
        this.callback = callback;
    }

    private static Logger logger = Logger.getLogger( JSZip.class );
    
    private String authenticatedUser;
    
    private ThreadGroup bgtg = new ThreadGroup( "fsback" );

    private final static int BUFFER_SIZE = 32*1024;
    
	private String tmpPath = System.getProperty( "java.io.tmpdir" );
	private String FILE_NAME_REGEX = "(.*)[*&%\\/\\|](.*)";
	private String encoding = "UTF-8";
	private String mimetype = "application/zip";
	private String model = null;
	private String stype = null;
	private String sNodeName;

	private ArrayList<String> getNodeRefs( ArrayList<String> nodeRefs , NodeRef nodeRef , boolean includeFolders ) throws Exception {
		List<FileInfo> files = fileFolderService.listFiles( nodeRef );
		List<FileInfo> folders = fileFolderService.listFolders( nodeRef );
		for( FileInfo file : files ){
			NodeRef childFileNodeRef = file.getNodeRef();
			String sChildFileNodeRef = childFileNodeRef.toString();
			nodeRefs.add( sChildFileNodeRef );
		}
		for( FileInfo folder : folders ){
			NodeRef childFolderNodeRef = folder.getNodeRef();
			if( includeFolders ) nodeRefs.add( childFolderNodeRef.toString() );
			getNodeRefs( nodeRefs , childFolderNodeRef , includeFolders );
		}
		return nodeRefs;
	}
	
	@SuppressWarnings("unused")
	public NodeRef toNode( String sParentNodeRef , String sNodeRef ) throws Exception {
		NodeRef zipNodeRef = null; 
		String nodeName;
		final NodeRef nodeRef = new NodeRef( sNodeRef );
		if( nodeRef == null ) {
			logger.error( "Node not found for provided node ref \"" + sNodeRef + "\"." );
			return null;
		}
		nodeName = (String) nodeService.getProperty( nodeRef , ContentModel.PROP_NAME ) + ".zip";
		zipNodeRef = toNode( sParentNodeRef , nodeName , sNodeRef , true );
		return zipNodeRef;
	}
	
	public NodeRef toNode( String sParentNodeRef , String nodeName , String sNodeRef ) throws Exception {
		return toNode( sParentNodeRef , nodeName , sNodeRef , true );
	}
	
	@SuppressWarnings("unused")
	public NodeRef toNode( String sParentNodeRef , String nodeName , String sNodeRef , boolean includeFolders ) throws Exception {
		NodeRef zipNodeRef = null; 
		ArrayList<String> nodeRefs = new ArrayList<String>();
		String startPoint = null;
		final NodeRef nodeRef = new NodeRef( sNodeRef );
		if( nodeRef == null ) {
			logger.error( "Node not found for provided node ref \"" + sNodeRef + "\"." );
			return null;
		}
		try{
			startPoint = nodeService.getPath( nodeRef ).toDisplayPath( nodeService , permissionService ) + "/";
			nodeRefs = getNodeRefs( nodeRefs , nodeRef , includeFolders );
			zipNodeRef = toNode( sParentNodeRef , nodeName , nodeRefs.toArray( new String[ nodeRefs.size() ] ) , startPoint );
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
		return zipNodeRef;
	}

	@SuppressWarnings("unused")
	public NodeRef toNode( String sParentNodeRef , String nodeName , String[] nodeRefs , String startPoint ) throws Exception {
		NodeRef zipNodeRef = null;
		final NodeRef parentNodeRef = new NodeRef( sParentNodeRef );
		if( parentNodeRef == null ) {
			logger.error( "Parent node not found for provided node ref \"" + sParentNodeRef + "\"." );
			return null;
		}
		if( nodeName.equals( "" ) || nodeName.matches( FILE_NAME_REGEX ) ) {
			logger.error( "Invalid node name provided (empty or containing special characters)." );
			return null;
		}
		if( alfContent.isContainer( parentNodeRef ) == false ) {
			logger.error( "Provide parent node \"" + nodeService.getProperty( parentNodeRef , ContentModel.PROP_NAME ) + "\" is not a folder." );
			return null;
		}
		if( nodeRefs.length == 0 ) {
			logger.error( "Empty set provided to be zipped." );
			return null;
		}

		sNodeName = nodeName;
		try{
		    // run in same thread
		    zipNodeRef = doInBackground( parentNodeRef , nodeName , nodeRefs , startPoint );
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
		return zipNodeRef;
    }
	
	@SuppressWarnings("unused")
	public void toNode( String sParentNodeRef , String sNodeRef , Function funct ) throws Exception {
		String nodeName;
		final NodeRef nodeRef = new NodeRef( sNodeRef );
		if( nodeRef == null ) {
			logger.error( "Node not found for provided node ref \"" + sNodeRef + "\"." );
			return;
		}
		nodeName = (String) nodeService.getProperty( nodeRef , ContentModel.PROP_NAME ) + ".zip";
		toNode( sParentNodeRef , nodeName , sNodeRef , true , funct );
	}
	
	public void toNode( String sParentNodeRef , String nodeName , String sNodeRef , Function funct ) throws Exception {
		toNode( sParentNodeRef , nodeName , sNodeRef , true , funct );
	}
	
	@SuppressWarnings("unused")
	public void toNode( String sParentNodeRef , String nodeName , String sNodeRef , boolean includeFolders , Function funct ) throws Exception {
		NodeRef zipNodeRef = null; 
		ArrayList<String> nodeRefs = new ArrayList<String>();
		String startPoint = null;
		final NodeRef nodeRef = new NodeRef( sNodeRef );
		if( nodeRef == null ) {
			logger.error( "Node not found for provided node ref \"" + sNodeRef + "\"." );
			return;
		}
		try{
			startPoint = nodeService.getPath( nodeRef ).toDisplayPath( nodeService , permissionService ) + "/";
			nodeRefs = getNodeRefs( nodeRefs , nodeRef , includeFolders );
			toNode( sParentNodeRef , nodeName , nodeRefs.toArray( new String[ nodeRefs.size() ] ) , startPoint , funct );
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unused")
	public void toNode( String sParentNodeRef , String nodeName , String[] nodeRefs , String startPoint , Function funct ) throws Exception {
		final NodeRef parentNodeRef = new NodeRef( sParentNodeRef );
		if( parentNodeRef == null ) {
			logger.error( "Parent node not found for provided node ref \"" + sParentNodeRef + "\"." );
			return;
		}
		if( nodeName.equals( "" ) || nodeName.matches( FILE_NAME_REGEX ) ) {
			logger.error( "Invalid node name provided (empty or containing special characters)." );
			return;
		}
		if( alfContent.isContainer( parentNodeRef ) == false ) {
			logger.error( "Provide parent node \"" + nodeService.getProperty( parentNodeRef , ContentModel.PROP_NAME ) + "\" is not a folder." );
			return;
		}
		if( nodeRefs.length == 0 ) {
			logger.error( "Empty set provided to be zipped." );
			return;
		}

		Callback.SharedScope.set( getScope() );

		sNodeName = nodeName;
		try{
			authenticatedUser = AuthenticationUtil.getFullyAuthenticatedUser();
			if( authenticatedUser == null || authenticatedUser.equals( "" ) ) {
				throw new Exception( "Unauthenticated" );
			}

		    // run in a background thread
		    DoInBackground doInBackground = new DoInBackground( parentNodeRef , nodeName , nodeRefs , startPoint , funct , this );
			Thread bgt = new Thread( bgtg , doInBackground );
			bgt.start();
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
    }

	class DoInBackground implements Runnable {
		private NodeRef parentNodeRef;
		private String nodeName;
		private String[] nodeRefs;
		private String startPoint;
		private Function funct;
		private JSZip jszip;
		static final int MAX_TRANSACTION_RETRIES = 1;
		
		private NodeRef zipNodeRef;
		
		DoInBackground( NodeRef parentNodeRef , String nodeName , String[] nodeRefs , String startPoint , Function funct , JSZip jszip ) {
	        this.parentNodeRef = parentNodeRef;
	        this.nodeName = nodeName;
	        this.nodeRefs = nodeRefs;
	        this.startPoint = startPoint;
	        this.funct = funct;
	        this.jszip = jszip;
	    }
		@Override
	    public void run() {
			// run it in transaction as script authenticated user
			AuthenticationUtil.setFullyAuthenticatedUser( jszip.authenticatedUser );
	   		RetryingTransactionCallback<NodeRef> txcallback = new RetryingTransactionCallback<NodeRef>() {
	   			@Override
	   			public NodeRef execute() throws Throwable {
					return jszip.doInBackground( parentNodeRef , nodeName , nodeRefs , startPoint );
	   			}
	    	};
	   		RetryingTransactionCallback<Object> txexcallback = new RetryingTransactionCallback<Object>() {
	   			@Override
	   			public Object execute() throws Throwable {
					Object[] args = new Object[1];
					args[0] = ( zipNodeRef != null ) ? zipNodeRef.toString() : null;
					Callback.AuthenticatedUser.set( authenticatedUser );
					callback.run( funct , args );
					return null;
	   			}
	    	};
	    	// run transactions
			try {
				RetryingTransactionHelper txnHelper = transactionService.getRetryingTransactionHelper();
				txnHelper.setMaxRetries( MAX_TRANSACTION_RETRIES );
				zipNodeRef = txnHelper.doInTransaction( txcallback , false , true );
				logger.info( "Created \"" + zipNodeRef + "\" from \"" + jszip.sNodeName + "\"." );
				// run callback
				// pass it created nodeRef (String)
				if( funct != null ){
					txnHelper.doInTransaction( txexcallback , false , true );
				}
				Thread.yield();
			}
			catch ( Exception e ) {
				logger.error( e );
				e.printStackTrace();
			}
	    }
	}

	private NodeRef doInBackground( NodeRef parentNodeRef , String nodeName , String[] nodeRefs , String startPoint ) throws Exception{
		NodeRef zipNodeRef = null;
		final String zipPath = toFS( nodeName , nodeRefs , startPoint );
		if( zipPath != null ) {
			File zipFile = new File( zipPath );
			InputStream content = new FileInputStream( zipFile );
			zipNodeRef = alfContent.createContentNode( parentNodeRef , nodeName , content , encoding , mimetype , model , stype );
			content.close();
		}
		return zipNodeRef;
	}
	
	private String toFS( String zipName , String[] nodeRefs , String startPoint ) throws Exception {
		String fullZipPath = null;
		if( zipName == null || zipName.equals( "" ) || nodeRefs.length == 0 ) return null;

		try{
			if( startPoint == null ) startPoint = "/";
			fullZipPath = tmpPath + "/" + zipName + ".zip";
			FileOutputStream fos = new FileOutputStream( fullZipPath );
			ZipOutputStream zos = new ZipOutputStream( fos );

			for ( String sNodeRef : nodeRefs ) {
				final NodeRef nodeRef = new NodeRef( sNodeRef );
		        InputStream inputStream = null;
				if( nodeRef != null ){
					String fileName = ( nodeService.getPath( nodeRef ).toDisplayPath( nodeService , permissionService ) + "/" + nodeService.getProperty( nodeRef, ContentModel.PROP_NAME ) ).replaceFirst( startPoint , "" );
					if( alfContent.isContainer( nodeRef ) == false ){
				        inputStream = contentService.getReader( nodeRef , ContentModel.PROP_CONTENT ).getContentInputStream();
					}
					else{
						fileName += "/";
					}
					logger.info( "Adding \"" + fileName + "\" to archive." );
					addToZipFile( fileName , inputStream , zos );
				}
			}
			zos.close();
			fos.close();
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}

		return fullZipPath;
    }

	private static void addToZipFile( String fileName , InputStream inputStream , ZipOutputStream zos) throws FileNotFoundException, IOException {
		ZipEntry zipEntry = new ZipEntry( fileName );
		zos.putNextEntry( zipEntry );

		if( inputStream != null ){
			byte[] bytes = new byte[BUFFER_SIZE];
			int length;
			while ( ( length = inputStream.read( bytes ) ) >= 0 ) {
				zos.write( bytes , 0 , length );
			}
			inputStream.close();
		}

		zos.closeEntry();
	}
}
