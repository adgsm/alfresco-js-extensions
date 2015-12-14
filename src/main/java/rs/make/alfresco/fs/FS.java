package rs.make.alfresco.fs;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.transaction.TransactionService;
import org.apache.log4j.Logger;
import org.mozilla.javascript.Function;

import rs.make.alfresco.alfcontent.AlfContent;
import rs.make.alfresco.callback.Callback;

public class FS extends Callback {
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

    private static Logger logger = Logger.getLogger( FS.class );
    
    private String authenticatedUser;
    
    private ThreadGroup bgtg = new ThreadGroup( "fsback" );
    
    private String sPath;

	@SuppressWarnings("unused")
	public NodeRef importFromFS( String path , String sParentNodeRef , String encoding , String mimetype , String model , String stype , Function funct ){
		if( path == null || path.equals( "" ) ) {
			logger.error( "Non-valid zip file path provided: " + path );
			return null;
		}
		if( sParentNodeRef == null || sParentNodeRef.equals( "" ) ) {
			logger.error( "Non-valid parent node ref provided: " + sParentNodeRef );
			return null;
		}

		Callback.SharedScope.set( getScope() );
		
		sPath = path;
		NodeRef fsNodeRef = null;
		try{
			NodeRef parentNodeRef = new NodeRef( sParentNodeRef );
			if( parentNodeRef == null ) {
				logger.error( "Not found parent node for provided nodeRef: " + sParentNodeRef );
				return null;
			}

			File file = new File( path );

			authenticatedUser = AuthenticationUtil.getFullyAuthenticatedUser();
			if( authenticatedUser == null || authenticatedUser.equals( "" ) ) {
				throw new Exception( "Unauthenticated" );
			}

		    String nodeName = file.getName();
			InputStream content = new FileInputStream( path );

			// run in a background thread
		    DoInBackground doInBackground = new DoInBackground( parentNodeRef , nodeName , content , encoding , mimetype , model , stype , funct , this );
			Thread bgt = new Thread( bgtg , doInBackground );
			bgt.start();
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
		return fsNodeRef;
	}

	class DoInBackground implements Runnable {
		private NodeRef parentNodeRef;
		private String nodeName;
		private InputStream content;
		private String encoding;
		private String mimetype;
		private String model;
		private String stype;
		private Function funct;
		private FS fs;
		static final int MAX_TRANSACTION_RETRIES = 1;
		
		private NodeRef importedNodeRef;
		
		DoInBackground( NodeRef parentNodeRef , String nodeName , InputStream content , String encoding , String mimetype , String model , String stype , Function funct , FS fs ) {
	        this.parentNodeRef = parentNodeRef;
	        this.nodeName = nodeName;
	        this.content = content;
	        this.encoding = encoding;
	        this.mimetype = mimetype;
	        this.model = model;
	        this.stype = stype;
	        this.funct = funct;
	        this.fs = fs;
	    }
		@Override
	    public void run() {
			// run it in transaction as script authenticated user
			AuthenticationUtil.setFullyAuthenticatedUser( fs.authenticatedUser );
	   		RetryingTransactionCallback<NodeRef> txcallback = new RetryingTransactionCallback<NodeRef>() {
	   			@Override
	   			public NodeRef execute() throws Throwable {
					return fs.doInBackground( parentNodeRef , nodeName , content , encoding , mimetype , model , stype );
	   			}
	    	};
	   		RetryingTransactionCallback<Object> txexcallback = new RetryingTransactionCallback<Object>() {
	   			@Override
	   			public Object execute() throws Throwable {
					Object[] args = new Object[1];
					args[0] = ( importedNodeRef != null ) ? importedNodeRef.toString() : null;
					Callback.AuthenticatedUser.set( authenticatedUser );
					callback.run( funct , args );
					return null;
	   			}
	    	};
	    	// run transactions
			try {
				RetryingTransactionHelper txnHelper = transactionService.getRetryingTransactionHelper();
				txnHelper.setMaxRetries( MAX_TRANSACTION_RETRIES );
				importedNodeRef = txnHelper.doInTransaction( txcallback , false , true );
				logger.info( "Imported \"" + fs.sPath + "\" into \"" + importedNodeRef + "\"." );
				// run callback
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

	private NodeRef doInBackground( NodeRef parentNodeRef , String nodeName , InputStream content , String encoding , String mimetype , String model , String stype ) throws Exception{
		NodeRef fsNodeRef = alfContent.createContentNode( parentNodeRef , nodeName , content , encoding , mimetype , model , stype );
		content.close();
		return fsNodeRef;
	}
}
