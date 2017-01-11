package rs.make.alfresco.jszip;

import java.lang.System;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;
import org.json.simple.JSONArray;
import org.mozilla.javascript.Function;

import org.alfresco.service.namespace.QName;

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
	private String DEFAULT_ENCODING = "UTF-8";
	private String ZIP_MIME = "application/zip";
	private String model = null;
	private String stype = null;
	private final String JSON_MIME = "application/json";

	private ObjectMapper objectMapper = new ObjectMapper();
	private ObjectNode properties = objectMapper.createObjectNode();
	private final String NAME_PREFIX = "__";
	private final String PROPERTIES_KEY = "_properties";
	private final String VALUE_KEY = "_value";
	private final String TYPE_KEY = "_type";
	private final String MIMETYPE_KEY = "_mimetype";
	private final String NAMESPACE_URI_KEY = "_namespace-uri";
	private final String LOCAL_NAME_KEY = "_local-name";
	private final String CLASS_KEY = "_class";
	private final String PRIMITIVE_ASSOC = "rs.make.PrimitiveAssoc";

	private class ZipPair {
		private final NodeRef zipNodeRef;
		private final NodeRef jsonNodeRef;

		private ZipPair( NodeRef zipNodeRef , NodeRef jsonNodeRef ) {
			this.zipNodeRef = zipNodeRef;
			this.jsonNodeRef = jsonNodeRef;
		}

		public NodeRef getZipNodeRef() {
			return zipNodeRef;
		}

		public NodeRef getJsonNodeRef() {
			return jsonNodeRef;
		}
	}

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

		try{
			// run in same thread
			ZipPair zipPair = doInBackground( parentNodeRef , nodeName , nodeRefs , startPoint , properties );
			zipNodeRef = zipPair.getZipNodeRef();
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

		try{
			authenticatedUser = AuthenticationUtil.getFullyAuthenticatedUser();
			if( authenticatedUser == null || authenticatedUser.equals( "" ) ) {
				throw new Exception( "Unauthenticated" );
			}

			// run in a background thread
			DoInBackground doInBackground = new DoInBackground( parentNodeRef , nodeName , nodeRefs , startPoint , properties , funct , this );
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
		private ObjectNode properties;
		private Function funct;
		private JSZip jszip;
		static final int MAX_TRANSACTION_RETRIES = 1;

		private NodeRef zipNodeRef;
		private NodeRef jsonNodeRef;

		DoInBackground( NodeRef parentNodeRef , String nodeName , String[] nodeRefs , String startPoint , ObjectNode properties , Function funct , JSZip jszip ) {
			this.parentNodeRef = parentNodeRef;
			this.nodeName = nodeName;
			this.nodeRefs = nodeRefs;
			this.startPoint = startPoint;
			this.properties = properties;
			this.funct = funct;
			this.jszip = jszip;
		}
		@Override
		public void run() {
			// run it in transaction as script authenticated user
			AuthenticationUtil.setFullyAuthenticatedUser( jszip.authenticatedUser );
			RetryingTransactionCallback<ZipPair> txcallback = new RetryingTransactionCallback<ZipPair>() {
				@Override
				public ZipPair execute() throws Throwable {
					return jszip.doInBackground( parentNodeRef , nodeName , nodeRefs , startPoint , properties );
				}
			};
			RetryingTransactionCallback<Object> txexcallback = new RetryingTransactionCallback<Object>() {
				@Override
				public Object execute() throws Throwable {
					Object[] args = new Object[2];
					args[0] = ( zipNodeRef != null ) ? zipNodeRef.toString() : null;
					args[1] = ( jsonNodeRef != null ) ? jsonNodeRef.toString() : null;
					Callback.AuthenticatedUser.set( authenticatedUser );
					callback.run( funct , args );
					return null;
				}
			};
			// run transactions
			try {
				RetryingTransactionHelper txnHelper = transactionService.getRetryingTransactionHelper();
				txnHelper.setMaxRetries( MAX_TRANSACTION_RETRIES );
				ZipPair zipPair = txnHelper.doInTransaction( txcallback , false , true );
				zipNodeRef = zipPair.getZipNodeRef();
				jsonNodeRef = zipPair.getJsonNodeRef();
				logger.log( Level.INFO , "Created \"" + zipNodeRef + "\", \"" + nodeService.getProperty( zipNodeRef , ContentModel.PROP_NAME ).toString() + "\"." );
				logger.log( Level.INFO , "Created \"" + jsonNodeRef + "\", \"" + nodeService.getProperty( jsonNodeRef , ContentModel.PROP_NAME ).toString() + "\"." );
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

	private ZipPair doInBackground( NodeRef parentNodeRef , String nodeName , String[] nodeRefs , String startPoint , ObjectNode properties ) throws Exception{
		ZipPair zipPair = null;
		final String zipPath = toFS( nodeName , nodeRefs , startPoint );
		if( zipPath != null ) {
			// zip
			File zipFile = new File( zipPath );
			InputStream content = new FileInputStream( zipFile );
			NodeRef zipNodeRef = alfContent.createContentNode( parentNodeRef , nodeName , content , DEFAULT_ENCODING , ZIP_MIME , model , stype );
			content.close();
			// json
			String json = objectMapper.writeValueAsString( properties );
			InputStream jsonis = new ByteArrayInputStream( json.getBytes( "UTF-8" ) );
			NodeRef jsonNodeRef = alfContent.createContentNode( parentNodeRef , nodeName.replaceAll( ".zip" , ".json" ) , jsonis , DEFAULT_ENCODING , JSON_MIME , model , stype );
			jsonis.close();
			zipPair = new ZipPair( zipNodeRef , jsonNodeRef );
		}
		return zipPair;
	}

	private String toFS( String zipName , String[] nodeRefs , String startPoint ) throws Exception {
		String fullZipPath = null;
		if( zipName == null || zipName.equals( "" ) || nodeRefs.length == 0 ) return null;

		try{
			this.properties.removeAll();
			if( startPoint == null ) startPoint = "/";
			logger.log( Level.DEBUG , "startPoint: " + startPoint );
			fullZipPath = tmpPath + "/" + zipName;
			FileOutputStream fos = new FileOutputStream( fullZipPath );
			ZipOutputStream zos = new ZipOutputStream( fos );

			nodeRefs_loop:
			for ( String sNodeRef : nodeRefs ) {
				final NodeRef nodeRef = new NodeRef( sNodeRef );
				InputStream inputStream = null;
				if( nodeRef != null ){
					String fileName = null;
					String zipFileName = null;
					String mimetype = null;
					try{
						String path = nodeService.getPath( nodeRef ).toDisplayPath( nodeService , permissionService );
						String nodeName = (String) nodeService.getProperty( nodeRef, ContentModel.PROP_NAME );
						fileName = ( path + "/" + nodeName ).replaceFirst( startPoint , "" );

						if( alfContent.isContainer( nodeRef ) == false ){
							ContentReader contentReader = contentService.getReader( nodeRef , ContentModel.PROP_CONTENT );
							inputStream = contentReader.getContentInputStream();
							mimetype = contentReader.getMimetype();
							zipFileName = fileName;
						}
						else{
							zipFileName = fileName + "/";
						}
					}
					catch( Exception e ){
						logger.error( e );
						e.printStackTrace();
						continue nodeRefs_loop;
					}

					logger.log( Level.INFO , "Exporting metadata for \"" + fileName + "\" into JSON." );
					updateJsonExport( fileName , startPoint , mimetype );

					logger.log( Level.INFO , "Adding \"" + zipFileName + "\" to archive." );
					addToZipFile( zipFileName , inputStream , zos );
					inputStream.close();
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

	private void updateJsonExport( String fileName , String startPoint , String mimetype ) {
		if( fileName == null || fileName.equals( "" ) ) return;
		ObjectNode pathNode = this.properties;
		String[] pathNodes = fileName.split( "/" );
		String fullPath = startPoint + fileName;
		ArrayList<NodeRef> nodeRefs = alfContent.getNodesFromDisplayPath( fullPath );
		if( nodeRefs == null ) {
			logger.error( "Can not retrieve node refs from provided path chunks. Provided path \"" + fullPath + "\" is probably invalid." );
			return;
		}
		int fullPathSize = nodeRefs.size();
		int fileNamePathSize = pathNodes.length;
		if( !this.nodeService.getProperty( nodeRefs.get( fullPathSize - 1 ) , ContentModel.PROP_NAME ).toString().equals( pathNodes[ fileNamePathSize - 1 ] ) ) {
			logger.error( "Something went wrong. Retrieved node refs are not matching path chunks. Provided path \"" + fullPath + "\" is probably invalid." );
			return;
		}

		for( int i=0; i<fileNamePathSize; i++ ){
			ObjectNode newNode = null;
			if( !pathNode.has( NAME_PREFIX + pathNodes[i] ) ){
				NodeRef nodeRef = nodeRefs.get( i + ( fullPathSize - fileNamePathSize ) );
				if( nodeRef == null ) {
					logger.error( "NodeRef retrieved for path chunk \"" + pathNodes[i] + "\" is null. Can not proceed further." );
					return;
				}
				QName nodeType = this.nodeService.getType( nodeRef );
				String nodeTypeNameSpaceURI = nodeType.getNamespaceURI();
				String nodeTypeLocalName = nodeType.getLocalName();

				newNode = pathNode.with( NAME_PREFIX + pathNodes[i] );
				ObjectNode newNodeType = newNode.with( TYPE_KEY );
				newNodeType.put( NAMESPACE_URI_KEY, nodeTypeNameSpaceURI );
				newNodeType.put( LOCAL_NAME_KEY, nodeTypeLocalName );

				if( mimetype != null ){
					newNode.put( MIMETYPE_KEY , mimetype );
				}

				Map<QName,Serializable> nodeProperties = this.nodeService.getProperties( nodeRef );
				ObjectNode newNodeProperties = newNode.with( PROPERTIES_KEY );
				for( Entry<QName,Serializable> property : nodeProperties.entrySet() ){
					extractProperty( nodeRef , newNodeProperties , property );
				}
			}
			else{
				newNode = pathNode.with( NAME_PREFIX + pathNodes[i] );
			}

			pathNode = newNode;
		}
	}

	@SuppressWarnings("unchecked")
	private void extractProperty( NodeRef nodeRef , ObjectNode newNodeProperties , Entry<QName,Serializable> property ){
		if( property != null && property.getKey() != null && property.getValue() != null ){
			QName key = property.getKey();
			String skey = key.toString();
			Serializable value = property.getValue();
			Class<? extends Serializable> valueClass = value.getClass();

			ObjectNode newNodePropertiesKey = newNodeProperties.with( skey );

			String sValueClass = valueClass.getName();
			String keyNSURI = key.getNamespaceURI();
			String keyLN = key.getLocalName();

			if( valueClass == Integer.class ){
				newNodePropertiesKey.put( VALUE_KEY , (int) value );
			}
			else if( valueClass == Long.class ){
				newNodePropertiesKey.put( VALUE_KEY , (Long) value );
			}
			else if( valueClass == Double.class ){
				newNodePropertiesKey.put( VALUE_KEY , (Double) value );
			}
			else if( valueClass == Float.class ){
				newNodePropertiesKey.put( VALUE_KEY , (Float) value );
			}
			else if( valueClass == Boolean.class ){
				newNodePropertiesKey.put( VALUE_KEY , (boolean) ( (Boolean) value ).booleanValue() );
			}
			else if( valueClass == Date.class ){
				newNodePropertiesKey.put( VALUE_KEY , (long) ( (Date) value ).getTime() );
			}
			else if( valueClass == ArrayList.class ){
				JSONArray jsonArray = new JSONArray();
				for( Object item : (ArrayList<?>) value ){
					jsonArray.add( item );
				}
				newNodePropertiesKey.put( VALUE_KEY , jsonArray.toJSONString() );
			}
			else if( valueClass == NodeRef.class ){
				newNodePropertiesKey.put( VALUE_KEY , this.nodeService.getPath( (NodeRef) value ).toDisplayPath( nodeService , permissionService ) + "/" + this.nodeService.getProperty( (NodeRef) value , ContentModel.PROP_NAME ) );
			}
			else if( AlfContent.isNodeRef( value.toString() ) ){
				NodeRef assocNodeRef = new NodeRef( value.toString() );
				if( this.nodeService.exists( assocNodeRef ) ){
					newNodePropertiesKey.put( VALUE_KEY , this.nodeService.getPath( assocNodeRef ).toDisplayPath( nodeService , permissionService ) + "/" + this.nodeService.getProperty( assocNodeRef , ContentModel.PROP_NAME )  );
					sValueClass = PRIMITIVE_ASSOC;
				}
				else{
					newNodePropertiesKey.put( VALUE_KEY , value.toString() );
				}
			}
			else{
				newNodePropertiesKey.put( VALUE_KEY , value.toString() );
			}

			newNodePropertiesKey.put( NAMESPACE_URI_KEY , keyNSURI );
			newNodePropertiesKey.put( LOCAL_NAME_KEY , keyLN );
			newNodePropertiesKey.put( CLASS_KEY , sValueClass );
		}
	}
}
