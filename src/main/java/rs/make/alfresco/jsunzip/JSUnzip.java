package rs.make.alfresco.jsunzip;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.activation.MimetypesFileTypeMap;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.transaction.TransactionService;
import org.apache.log4j.Logger;
import org.mozilla.javascript.Function;

import rs.make.alfresco.alfcontent.AlfContent;
import rs.make.alfresco.callback.Callback;

/**
 * @desc expose unzip functionality to Alf JS
 * 
 * @filename JSUnzip.java
 * @author Momcilo Dzunic (momcilo@dzunic.net)
 * @licence MIT
 */
public class JSUnzip extends Callback {	
	protected ServiceRegistry serviceRegistry;
    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }
    public void setServiceRegistry( ServiceRegistry serviceRegistry ) {
        this.serviceRegistry = serviceRegistry;
    }

    protected TransactionService transactionService;
    public TransactionService getTransactionService() {
        return transactionService;
    }
    public void setTransactionService( TransactionService transactionService ) {
        this.transactionService = transactionService;
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

    private static Logger logger = Logger.getLogger( JSUnzip.class );

    private String authenticatedUser;

    private final String MIME_ZIP = "application/zip";
    private ThreadGroup bgtg = new ThreadGroup( "alfuzipback" );
    private ThreadGroup btg = new ThreadGroup( bgtg , "alfuzipbatch" );
    private ThreadGroup tg = new ThreadGroup( btg , "alfuzip" );
    private final int ENTRIES_BATCH_SIZE = 50;
    private final int BUFFER_SIZE = 32*1024;

	private NodeRef nodeRef;
	private NodeRef parentNodeRef;
	private boolean hasMoreEntries = false;
	
	public ArrayList<String> createdNodes = new ArrayList<String>();
	
	public void fromNode( String sNodeRef , String sParentNodeRef , Function funct ) throws Exception {
		nodeRef = new NodeRef( sNodeRef );
		parentNodeRef = new NodeRef( sParentNodeRef );

		try{
			if( nodeRef == null ) {
				throw new Exception( "Invalid zip node ref provided " + sNodeRef );
			}
			if( parentNodeRef == null ) {
				throw new Exception( "Invalid destination node ref provided " + sParentNodeRef );
			}

			authenticatedUser = AuthenticationUtil.getFullyAuthenticatedUser();
			if( authenticatedUser == null || authenticatedUser.equals( "" ) ) {
				throw new Exception( "Unauthenticated" );
			}

			ContentReader contentReader = serviceRegistry.getContentService().getReader( nodeRef , ContentModel.PROP_CONTENT );
	        String mimetype = contentReader.getMimetype();
	        if( mimetype.equalsIgnoreCase( MIME_ZIP ) == false ){
				throw new Exception( "Invalid mimetype of source node: \"" + mimetype + "\". It should be \"" + MIME_ZIP + "\"." );
	        }

			Callback.SharedScope.set( getScope() );
			
			// initialize
	        createdNodes.clear();
	        init( contentReader , funct );
			logger.info( "Unzip processing started." );
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
    }

	private void init( ContentReader contentReader , Function funct ) throws Exception{
	    // do it in a background thread
	    DoInBackground doInBackground = new DoInBackground( contentReader , funct , this );
		Thread bgt = new Thread( bgtg , doInBackground );
		bgt.start();
	}

	class DoInBackground implements Runnable {
		private ContentReader contentReader;
		private Function funct;
		private JSUnzip jsunzip;
		
		DoInBackground( ContentReader contentReader , Function funct , JSUnzip jsunzip ) {
	        this.contentReader = contentReader;
	        this.funct = funct;
	        this.jsunzip = jsunzip;
	    }
		@Override
	    public void run() {
			try {
				jsunzip.doInBackground( contentReader , funct );
			}
			catch ( Exception e ) {
				logger.error( e );
				e.printStackTrace();
			}
	    }
	}

	private void doInBackground( ContentReader contentReader , Function funct ) throws Exception{
		InputStream is = contentReader.getContentInputStream();
		BufferedInputStream bis = new BufferedInputStream( is );
		ZipInputStream zis = new ZipInputStream( bis );

		// zip files can have huge number of entries
		// process it in batches
		processBatches( zis , funct );

		zis.close();
		bis.close();
		is.close();
	}

	private void processBatches( ZipInputStream zis , final Function funct ) throws Exception{
		final int MAX_TRANSACTION_RETRIES = 1;
		DoInBatches doDoInBatches = new DoInBatches( zis , this );
		Thread bt = new Thread( btg , doDoInBatches );
		bt.start();
		bt.join();
		if( hasMoreEntries ) {
			processBatches( doDoInBatches.zis , funct );
		}
		else{
			logger.info( "Unzip processing concluded." );
			// run callback
	   		RetryingTransactionCallback<Object> txexcallback = new RetryingTransactionCallback<Object>() {
	   			@Override
	   			public Object execute() throws Throwable {
					Object[] args = new Object[1];
					String[] nodes = new String[ createdNodes.size() ];
					createdNodes.toArray( nodes );
					// pass it array of all created nodeRefs(Strings)
					args[0] = nodes;
					Callback.AuthenticatedUser.set( authenticatedUser );
					callback.run( funct , args );
					return null;
	   			}
	    	};
			try {
				RetryingTransactionHelper txnHelper = transactionService.getRetryingTransactionHelper();
				txnHelper.setMaxRetries( MAX_TRANSACTION_RETRIES );
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

	class DoInBatches implements Runnable {
		private ZipInputStream zis;
		private JSUnzip jsunzip;
		
		DoInBatches( ZipInputStream zis , JSUnzip jsunzip ) {
	        this.zis = zis;
	        this.jsunzip = jsunzip;
	    }
		@Override
	    public void run() {
			try {
				zis = jsunzip.doInBatches( zis );
			}
			catch ( Exception e ) {
				logger.error( e );
				e.printStackTrace();
			}
	    }
	}
	
	private ZipInputStream doInBatches( ZipInputStream zis ) throws Exception{
		int entryCount = 0;
		ZipEntry zipEntry;
		List<Thread> threadList = new ArrayList<Thread>();
		while( ( zipEntry = zis.getNextEntry() ) != null && entryCount <= ENTRIES_BATCH_SIZE ){
			entryCount++;

			// get zip entry input streams for all entries in a batch
			InputStream bzis = null;
			String fileName = zipEntry.getName();
			// do it for files only
			if( !fileName.endsWith( "/" ) ) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				byte[] buffer = new byte[BUFFER_SIZE];
				int len;
				while ( ( len = zis.read( buffer ) ) > -1 ) {
				    baos.write( buffer , 0 , len ) ;
				}
				baos.flush();
				byte[] byteArray = baos.toByteArray();
				bzis = new ByteArrayInputStream( byteArray );
		        baos.close();
			}

			DoUnpackAndWriteEntryInThread doUnpackAndWriteEntryInThread = new DoUnpackAndWriteEntryInThread( zipEntry , bzis , this );
	        Thread t = new Thread( tg , doUnpackAndWriteEntryInThread );
	        threadList.add( t );

			if( bzis != null ) bzis.close();
		}
		hasMoreEntries = ( zipEntry == null ) ? false : true;
		for ( Thread t : threadList ) {
			t.start();
			t.join();
		}
		return zis;
	}

	class DoUnpackAndWriteEntryInThread implements Runnable {
		private ZipEntry zipEntry;
		private InputStream fzis;
		private JSUnzip jsunzip;
		static final int MAX_TRANSACTION_RETRIES = 1;
		
		DoUnpackAndWriteEntryInThread( ZipEntry zipEntry , InputStream fzis , JSUnzip jsunzip ) {
	        this.zipEntry = zipEntry;
	        this.fzis = fzis;
	        this.jsunzip = jsunzip;
	    }
		@Override
	    public void run() {
			// run it in transaction as script authenticated user
			AuthenticationUtil.setFullyAuthenticatedUser( jsunzip.authenticatedUser );
	   		RetryingTransactionCallback<ArrayList<NodeRef> > txcallback = new RetryingTransactionCallback<ArrayList<NodeRef> >() {
	   			@Override
	   			public ArrayList<NodeRef> execute() throws Throwable {
	   				return jsunzip.unpackEntry( zipEntry , fzis );
	   			}
	    	};
	    	// run transaction
			try {
				RetryingTransactionHelper txnHelper = transactionService.getRetryingTransactionHelper();
				txnHelper.setMaxRetries( MAX_TRANSACTION_RETRIES );
				logger.info( "Processing " + zipEntry.getName() + "." );
				ArrayList<NodeRef> zipEntryNodeRefs = txnHelper.doInTransaction( txcallback , false , true );
				Set<NodeRef> distinctNodeRefsList = new LinkedHashSet<>( zipEntryNodeRefs );
				zipEntryNodeRefs.clear();
				zipEntryNodeRefs.addAll( distinctNodeRefsList );
				for( NodeRef zipEntryNodeRef : zipEntryNodeRefs ){
					// add each created node entry ref into array list to be passed into a callback
					jsunzip.createdNodes.add( ( zipEntryNodeRef != null ) ? zipEntryNodeRef.toString() : null );
				}
				Thread.yield();
			}
			catch ( Throwable e ) {
				logger.error( e );
				e.printStackTrace();
			}
	    }
	}
	
	private ArrayList<NodeRef> unpackEntry( ZipEntry zipEntry , InputStream fzis ) throws Exception
	{
		String name = zipEntry.getName();
		String fmimetype = new MimetypesFileTypeMap().getContentType( zipEntry.getName() );
		return writeEntry( name , fmimetype , fzis );
	}
	
	private ArrayList<NodeRef> writeEntry( String name , String fmimetype , InputStream fzis ) throws Exception
	{
		ArrayList<NodeRef> zipEntryNodeRefs = new ArrayList<NodeRef>();
    	String[] pathPieces = name.split( "/" );
    	int i = 0;
		// traverse folders
		NodeRef floatingParentNodeRef = parentNodeRef;
		int folderDepth = ( fzis == null ) ? pathPieces.length : ( pathPieces.length - 1 );
		while( i < folderDepth ){
			floatingParentNodeRef = alfContent.createFolderNode( floatingParentNodeRef , pathPieces[ i ] , null , null );
			zipEntryNodeRefs.add( floatingParentNodeRef );
			i++;
		}
		// process file zip entries
		if( fzis != null ){
			String fileName = pathPieces[ folderDepth ];
			NodeRef zipEntryNodeRef = alfContent.createContentNode( floatingParentNodeRef , fileName , fzis , null , fmimetype , null , null );
			zipEntryNodeRefs.add( zipEntryNodeRef );
		}
		return zipEntryNodeRefs;
	}
}
