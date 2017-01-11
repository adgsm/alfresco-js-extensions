package rs.make.alfresco.jsunzip;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.activation.MimetypesFileTypeMap;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.site.SiteModel;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.InvalidNodeRefException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.namespace.InvalidQNameException;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.json.simple.JSONArray;
import org.json.simple.JSONValue;
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

	protected TransactionService transactionService;
	public TransactionService getTransactionService() {
		return transactionService;
	}
	public void setTransactionService( TransactionService transactionService ) {
		this.transactionService = transactionService;
	}

	protected SiteService siteService;
	public SiteService getSiteService() {
		return siteService;
	}
	public void setSiteService( SiteService siteService ) {
		this.siteService = siteService;
	}

	protected DictionaryService dictionaryService;
	public DictionaryService getDictionaryService() {
		return dictionaryService;
	}
	public void setDictionaryService( DictionaryService dictionaryService ) {
		this.dictionaryService = dictionaryService;
	}

	protected SearchService searchService;
	public SearchService getSearchService() {
		return searchService;
	}
	public void setSearchService( SearchService searchService ) {
		this.searchService = searchService;
	}

	protected ServiceRegistry serviceRegistry;
	public ServiceRegistry getServiceRegistry() {
		return serviceRegistry;
	}
	public void setServiceRegistry( ServiceRegistry serviceRegistry ) {
		this.serviceRegistry = serviceRegistry;
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

	private final static String TOKEN_SEPARATOR = "\\A";
	private final String MIME_ZIP = "application/zip";
	private final String MIME_JSON = "application/json";
	private ThreadGroup bgtg = new ThreadGroup( "alfuzipback" );
	private ThreadGroup btg = new ThreadGroup( bgtg , "alfuzipbatch" );
	private ThreadGroup tg = new ThreadGroup( btg , "alfuzip" );
	private final int ENTRIES_BATCH_SIZE = 50;
	private final int BUFFER_SIZE = 32*1024;

	private NodeRef nodeRef;
	private NodeRef parentNodeRef;
	private boolean hasMoreEntries = false;
	String[] nodes = null;
	Thread bgt;

	private ObjectMapper objectMapper = new ObjectMapper();
	private final String NAME_PREFIX = "__";
	private final String PROPERTIES_KEY = "_properties";
	private final String VALUE_KEY = "_value";
	private final String TYPE_KEY = "_type";
	private final String MIMETYPE_KEY = "_mimetype";
	private final String NAMESPACE_URI_KEY = "_namespace-uri";
	private final String LOCAL_NAME_KEY = "_local-name";
	private final String CLASS_KEY = "_class";
	private final String PRIMITIVE_ASSOC = "rs.make.PrimitiveAssoc";

	private final String[] BANNED_PROPERTY_KEYS = {
		"{http://www.alfresco.org/model/system/1.0}store-protocol",
		"{http://www.alfresco.org/model/system/1.0}store-identifier",
		"{http://www.alfresco.org/model/system/1.0}node-dbid",
		"{http://www.alfresco.org/model/system/1.0}node-uuid",
		"{http://www.alfresco.org/model/content/1.0}content",
		"{http://www.alfresco.org/model/content/1.0}homeFolder",
		"{http://www.alfresco.org/model/content/1.0}homeFolderProvider",
		"{http://www.alfresco.org/model/content/1.0}emailFeedId"
	};

	private final QName FUTURE_NODE_QNAME = QName.createQName( NamespaceService.CONTENT_MODEL_1_0_URI , "futureNode" );

	public ArrayList<String> createdNodes = new ArrayList<String>();

	private NodeRef findJsonProperties( NodeRef zipNodeRef ){
		NodeRef jsonNodeRef = null;
		try{
			String jsonFileName = (String) this.nodeService.getProperty( zipNodeRef, ContentModel.PROP_NAME ).toString().replaceAll( ".zip" , ".json" );
			ChildAssociationRef parentAssoc = this.nodeService.getPrimaryParent( nodeRef );
			NodeRef parentNodeRef = parentAssoc.getParentRef();
			jsonNodeRef = this.nodeService.getChildByName( parentNodeRef , ContentModel.ASSOC_CONTAINS , jsonFileName );
		}
		catch( InvalidNodeRefException e ){
			logger.error( e );
			e.printStackTrace();
		}
		return jsonNodeRef;
	}

	private static String inputStreamToString( InputStream is ) {
		String result = null;

		Scanner s = new Scanner( is );
		try {
			result = s.useDelimiter( TOKEN_SEPARATOR ).hasNext() ? s.next() : ""; // beginning of the input boundary -> (\A)
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
		finally{
			s.close();
		}

		return result;
	}

	public String[] fromNode( String sNodeRef , String sParentNodeRef ) throws Exception {
		fromNode( sNodeRef , sParentNodeRef , null );
		return nodes;
	}

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

			ContentReader contentReader = this.contentService.getReader( nodeRef , ContentModel.PROP_CONTENT );
			String mimetype = contentReader.getMimetype();
			if( mimetype.equalsIgnoreCase( MIME_ZIP ) == false ){
				throw new Exception( "Invalid mimetype of source node: \"" + mimetype + "\". It should be \"" + MIME_ZIP + "\"." );
			}

			ContentReader propertiesContentReader = null;
			NodeRef jsonNodeRef = findJsonProperties( nodeRef );
			if( jsonNodeRef != null ){
				propertiesContentReader = this.contentService.getReader( jsonNodeRef , ContentModel.PROP_CONTENT );
				String jsonmimetype = propertiesContentReader.getMimetype();
				if( jsonmimetype.equalsIgnoreCase( MIME_JSON ) == false ){
					throw new Exception( "Invalid mimetype of properties node: \"" + jsonmimetype + "\". It should be \"" + MIME_JSON + "\"." );
				}
			}

			Callback.SharedScope.set( getScope() );

			// initialize
			createdNodes.clear();
			if( funct != null ) {
				init( contentReader , propertiesContentReader , funct );
			}
			else{
				nodes = init( contentReader , propertiesContentReader );
			}
			logger.log( Level.INFO , "Unzip processing started." );
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
	}

	private String[] init( ContentReader contentReader , ContentReader propertiesContentReader ) throws Exception{
		init( contentReader , propertiesContentReader , null );
		bgt.join();
		logger.log( Level.INFO , "Unzip processing concluded." );
		String[] nodes = new String[ createdNodes.size() ];
		createdNodes.toArray( nodes );
		return nodes;
	}

	private void init( ContentReader contentReader , ContentReader propertiesContentReader , Function funct ) throws Exception{
		// do in a background thread
		DoInBackground doInBackground = new DoInBackground( contentReader , propertiesContentReader , funct , this );
		bgt = new Thread( bgtg , doInBackground );
		bgt.start();
	}

	class DoInBackground implements Runnable {
		private ContentReader contentReader;
		private ContentReader propertiesContentReader;
		private Function funct;
		private JSUnzip jsunzip;

		DoInBackground( ContentReader contentReader , ContentReader propertiesContentReader , Function funct , JSUnzip jsunzip ) {
			this.contentReader = contentReader;
			this.propertiesContentReader = propertiesContentReader;
			this.funct = funct;
			this.jsunzip = jsunzip;
		}
		@Override
		public void run() {
			try {
				jsunzip.doInBackground( contentReader , propertiesContentReader , funct );
			}
			catch ( Exception e ) {
				logger.error( e );
				e.printStackTrace();
			}
		}
	}

	private void doInBackground( ContentReader contentReader , ContentReader propertiesContentReader , Function funct ) throws Exception{
		InputStream is = contentReader.getContentInputStream();
		BufferedInputStream bis = new BufferedInputStream( is );
		ZipInputStream zis = new ZipInputStream( bis );

		JsonNode properties = null;
		if( propertiesContentReader != null ){
			InputStream isprop = propertiesContentReader.getContentInputStream();
			try{
				String json = inputStreamToString( isprop );
				properties = objectMapper.readTree( json );
			}
			catch( Exception e ){
				logger.error( e );
				e.printStackTrace();
			}
			finally{
				isprop.close();
			}
		}
		// zip files can have huge number of entries
		// process it in batches
		processBatches( zis , properties , funct );

		zis.close();
		bis.close();
		is.close();
	}

	private void processBatches( ZipInputStream zis , JsonNode properties , final Function funct ) throws Exception{
		final int MAX_TRANSACTION_RETRIES = 1;
		DoInBatches doDoInBatches = new DoInBatches( zis , properties , this );
		Thread bt = new Thread( btg , doDoInBatches );
		bt.start();
		bt.join();
		if( hasMoreEntries ) {
			processBatches( doDoInBatches.zis , properties , funct );
		}
		else{
			// run callback
			RetryingTransactionCallback<Object> txexcallback = new RetryingTransactionCallback<Object>() {
				@Override
				public Object execute() throws Throwable {
					// Compose callback
					Object[] args = new Object[1];
					String[] nodes = new String[ createdNodes.size() ];
					createdNodes.toArray( nodes );
					// pass it array of all created nodeRefs(Strings)
					args[0] = nodes;
					Callback.AuthenticatedUser.set( authenticatedUser );
					callback.run( funct , args );
					logger.log( Level.INFO , "Unzip processing concluded." );
					return null;
				}
			};
			try {
				// retry futureNodes
				futureNodes( authenticatedUser );
				// callback
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
		private JsonNode properties;
		private JSUnzip jsunzip;

		DoInBatches( ZipInputStream zis, JsonNode properties , JSUnzip jsunzip ) {
			this.zis = zis;
			this.properties = properties;
			this.jsunzip = jsunzip;
		}
		@Override
		public void run() {
			try {
				zis = jsunzip.doInBatches( zis , properties );
			}
			catch ( Exception e ) {
				logger.error( e );
				e.printStackTrace();
			}
		}
	}

	private ZipInputStream doInBatches( ZipInputStream zis , JsonNode properties ) throws Exception{
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

			DoUnpackAndWriteEntryInThread doUnpackAndWriteEntryInThread = new DoUnpackAndWriteEntryInThread( zipEntry , bzis , properties , this );
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
		private JsonNode properties;
		private JSUnzip jsunzip;
		static final int MAX_TRANSACTION_RETRIES = 1;

		DoUnpackAndWriteEntryInThread( ZipEntry zipEntry , InputStream fzis , JsonNode properties , JSUnzip jsunzip ) {
			this.zipEntry = zipEntry;
			this.fzis = fzis;
			this.properties = properties;
			this.jsunzip = jsunzip;
		}
		@Override
		public void run() {
			// run it in transaction as script authenticated user
			AuthenticationUtil.setFullyAuthenticatedUser( jsunzip.authenticatedUser );
			RetryingTransactionCallback<ArrayList<NodeRef> > txcallback = new RetryingTransactionCallback<ArrayList<NodeRef> >() {
				@Override
				public ArrayList<NodeRef> execute() throws Throwable {
					return jsunzip.unpackEntry( zipEntry , fzis , properties );
				}
			};
			// run transaction
			try {
				RetryingTransactionHelper txnHelper = transactionService.getRetryingTransactionHelper();
				txnHelper.setMaxRetries( MAX_TRANSACTION_RETRIES );
				logger.log( Level.INFO , "Processing " + zipEntry.getName() + "." );
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

	private ArrayList<NodeRef> unpackEntry( ZipEntry zipEntry , InputStream fzis , JsonNode properties ) throws Exception {
		String name = zipEntry.getName();
		String fmimetype = new MimetypesFileTypeMap().getContentType( zipEntry.getName() );
		logger.debug( "Zip entry mimetype: \"" + fmimetype + "\"." );
		return writeEntry( name , fmimetype , fzis , properties );
	}

	private ArrayList<NodeRef> writeEntry( String name , String fmimetype , InputStream fzis , JsonNode properties ) throws Exception {
		ArrayList<NodeRef> zipEntryNodeRefs = new ArrayList<NodeRef>();
		String[] pathPieces = name.split( "/" );
		int i = 0;
		// traverse folders
		NodeRef floatingParentNodeRef = parentNodeRef;
		JsonNode floatingParentJsonRef = properties;
		int folderDepth = ( fzis == null ) ? pathPieces.length : ( pathPieces.length - 1 );
		logger.debug( "Zip entry depth: \"" + folderDepth + "\"." );
		while( i < folderDepth ){
			JsonProperties jsonProperties = parseProperties( floatingParentJsonRef , pathPieces[ i ] );
			floatingParentJsonRef = jsonProperties.getJsonNode();
			Map<QName, Serializable> nodeProperties = jsonProperties.getNodeProperties();
			boolean isSite = false;
			QName qtype = null;
			if( jsonProperties.getModel() != null && jsonProperties.getSType() != null ){
				try{
					qtype = QName.createQName( jsonProperties.getModel() , jsonProperties.getSType() );
					isSite = this.dictionaryService.isSubClass( qtype , SiteModel.TYPE_SITE ) == true && floatingParentNodeRef.equals( this.siteService.getSiteRoot() );
				}
				catch( InvalidQNameException e ){
					logger.error( e );
					e.printStackTrace();
				}
			}
			if( isSite == true ){
				// create site
				floatingParentNodeRef = alfContent.createSiteNode( null , pathPieces[ i ] , null , null , null , nodeProperties );
			}
			else{
				// create folder
				String model = jsonProperties.getModel();
				String sType = jsonProperties.getSType();
				if( qtype != null && qtype.equals( SiteModel.TYPE_SITE ) ){
					logger.error( "Node \"" + pathPieces[ i ] + "\" has share site type but it is placed at inappropriate path (its parent is not share site root). Changing its type to cm:folder. Fix this error manually after creation." );
					model = ContentModel.TYPE_FOLDER.getNamespaceURI();
					sType = ContentModel.TYPE_FOLDER.getLocalName();
				}
				floatingParentNodeRef = alfContent.createFolderNode( floatingParentNodeRef , pathPieces[ i ] , model , sType , ( nodeProperties.size() > 0 ) ? nodeProperties : null );
			}
			zipEntryNodeRefs.add( floatingParentNodeRef );
			i++;
		}
		// process file zip entries
		if( fzis != null ){
			String fileName = pathPieces[ folderDepth ];
			JsonProperties jsonProperties = parseProperties( floatingParentJsonRef , fileName );
			floatingParentJsonRef = jsonProperties.getJsonNode();
			Map<QName, Serializable> nodeProperties = jsonProperties.getNodeProperties();
			NodeRef zipEntryNodeRef = alfContent.createContentNode( floatingParentNodeRef , fileName , fzis , null , ( jsonProperties.getMimetype() != null ) ? jsonProperties.getMimetype() : fmimetype , jsonProperties.getModel() , jsonProperties.getSType() , ( nodeProperties.size() > 0 ) ? nodeProperties : null );
			zipEntryNodeRefs.add( zipEntryNodeRef );
		}
		return zipEntryNodeRefs;
	}

	private JsonProperties parseProperties( JsonNode jsonRef , String name ){
		JsonProperties jsonProperties = new JsonProperties();
		String model = null;
		String stype = null;
		String mimetype = null;
		Map<QName, Serializable> nodeProperties = new HashMap<QName, Serializable>();
		try{
			if( jsonRef != null ){
				jsonRef = jsonRef.path( NAME_PREFIX + name );
				if ( !jsonRef.isMissingNode() ) {
					JsonNode typeJsonRef = jsonRef.path( TYPE_KEY );
					if( !typeJsonRef.isMissingNode() ){
						model = typeJsonRef.get( NAMESPACE_URI_KEY ).getTextValue();
						stype = typeJsonRef.get( LOCAL_NAME_KEY ).getTextValue();
					}
					JsonNode mimeJsonRef = jsonRef.path( MIMETYPE_KEY );
					if( !mimeJsonRef.isMissingNode() ){
						mimetype = mimeJsonRef.getTextValue();
					}
					JsonNode propertiesJsonRef = jsonRef.path( PROPERTIES_KEY );
					if( !propertiesJsonRef.isMissingNode() ){
						Iterator<Entry<String,JsonNode>> propertiesObj = propertiesJsonRef.getFields();
						while( propertiesObj.hasNext() ){
							Entry<String,JsonNode> propertyObj = propertiesObj.next();
							String propertyKey = propertyObj.getKey();
							if( Arrays.asList( BANNED_PROPERTY_KEYS ).indexOf( propertyKey ) > -1 ) continue;

							JsonNode property = propertyObj.getValue();
							JsonNode propertyValue = property.path( VALUE_KEY );
							JsonNode propertyNameSpacesURI = property.path( NAMESPACE_URI_KEY );
							JsonNode propertyLocalName = property.path( LOCAL_NAME_KEY );
							JsonNode propertyClass = property.path( CLASS_KEY );

							if( !propertyNameSpacesURI.isMissingNode() && !propertyLocalName.isMissingNode() && !propertyValue.isMissingNode() && !propertyClass.isMissingNode() ){
								String propertyModel = propertyNameSpacesURI.getTextValue();
								String propertyType = propertyLocalName.getTextValue();
								QName propertyQName = QName.createQName( propertyModel , propertyType );

								if( propertyClass.getTextValue().equals( Integer.class.getName() ) ){
									nodeProperties.put( propertyQName , (int) propertyValue.getIntValue() );
								}
								else if( propertyClass.getTextValue().equals( Long.class.getName() ) ){
									nodeProperties.put( propertyQName , (Long) propertyValue.getLongValue() );
								}
								else if( propertyClass.getTextValue().equals( Double.class.getName() ) ){
									nodeProperties.put( propertyQName , (Double) propertyValue.getDoubleValue() );
								}
								else if( propertyClass.getTextValue().equals( Float.class.getName() ) ){
									nodeProperties.put( propertyQName , (Float) propertyValue.getNumberValue() );
								}
								else if( propertyClass.getTextValue().equals( Boolean.class.getName() ) ){
									nodeProperties.put( propertyQName , (Boolean) propertyValue.getBooleanValue() );
								}
								else if( propertyClass.getTextValue().equals( Date.class.getName() ) ){
									nodeProperties.put( propertyQName , new Date( propertyValue.getLongValue() ) );
								}
								else if( propertyClass.getTextValue().equals( Locale.class.getName() ) ){
									nodeProperties.put( propertyQName , new Locale( propertyValue.getTextValue() ) );
								}
								else if( propertyClass.getTextValue().equals( ArrayList.class.getName() ) ){
									Object jsonObj = JSONValue.parse( propertyValue.getTextValue() );
									JSONArray jsonArray = (JSONArray) jsonObj;
									ArrayList<String> arrayList = new ArrayList<String>();
									if ( jsonArray != null ) {
										int len = jsonArray.size();
										for ( int i=0; i<len; i++ ){
											arrayList.add( jsonArray.get( i ).toString() );
										}
									}
									nodeProperties.put( propertyQName , arrayList );
								}
								else if( propertyClass.getTextValue().equals( NodeRef.class.getName() ) ){
									String val = propertyValue.getTextValue();
									ArrayList<NodeRef> nodeRefs = alfContent.getNodesFromDisplayPath( val );
									if( nodeRefs.size() > 0 ){
										NodeRef nodeRef = ( nodeRefs.size() > 0 ) ? nodeRefs.get( nodeRefs.size() - 1 ) : null;
										nodeProperties.put( propertyQName , nodeRef );
									}
									else{
										// future node (node yet to be created)
										nodeProperties.put( FUTURE_NODE_QNAME , val + "|" + NodeRef.class.getName() + "|" + propertyQName.getNamespaceURI() + "|" + propertyQName.getLocalName() );
									}
								}
								else if( propertyClass.getTextValue().equals( PRIMITIVE_ASSOC ) ){
									String val = propertyValue.getTextValue();
									ArrayList<NodeRef> nodeRefs = alfContent.getNodesFromDisplayPath( val );
									if( nodeRefs.size() > 0 ){
										NodeRef nodeRef = ( nodeRefs.size() > 0 ) ? nodeRefs.get( nodeRefs.size() - 1 ) : null;
										nodeProperties.put( propertyQName , nodeRef.toString() );
									}
									else{
										// future node (node yet to be created)
										nodeProperties.put( FUTURE_NODE_QNAME , val + "|" + PRIMITIVE_ASSOC + "|" + propertyQName.getNamespaceURI() + "|" + propertyQName.getLocalName() );
									}
								}
								else if( propertyClass.getTextValue().equals( String.class.getName() ) ){
									nodeProperties.put( propertyQName , propertyValue.getTextValue() );
								}
								else {
									logger.warn( "Unhandled property class \"" + propertyClass.getTextValue() + "\"." );
								}
							}
						}
					}
				}
			}
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
		jsonProperties.setJsonNode( jsonRef );
		jsonProperties.setModel( model );
		jsonProperties.setSType( stype );
		jsonProperties.setMimetype( mimetype );
		jsonProperties.setNodeProperties( nodeProperties );
		return jsonProperties;
	}

	private class JsonProperties {
		private JsonNode jsonNode;
		private String model;
		private String stype;
		private String mimetype;
		private Map<QName, Serializable> nodeProperties;

		public JsonNode getJsonNode() {
			return jsonNode;
		}

		public String getModel() {
			return model;
		}

		public String getSType() {
			return stype;
		}

		public String getMimetype() {
			return mimetype;
		}

		public Map<QName, Serializable> getNodeProperties() {
			return nodeProperties;
		}

		public void setJsonNode( JsonNode jsonNode ) {
			this.jsonNode = jsonNode;
		}

		public void setModel( String model ) {
			this.model = model;
		}

		public void setSType( String stype ) {
			this.stype = stype;
		}

		public void setMimetype( String mimetype ) {
			this.mimetype = mimetype;
		}

		public void setNodeProperties( Map<QName, Serializable> nodeProperties ) {
			this.nodeProperties = nodeProperties;
		}
	}

	private void futureNodes( String authenticatedUser ) {
		logger.info( "Processing properties pointing to nodes which were not ready at the time of setting properties initially." );
		AuthenticationUtil.setFullyAuthenticatedUser( authenticatedUser );
		NodeRef rootNode = nodeService.getRootNode( StoreRef.STORE_REF_WORKSPACE_SPACESSTORE );
		logger.log( Level.DEBUG , "Authenticated as " + AuthenticationUtil.getFullyAuthenticatedUser() );
		List<NodeRef> fNodes = this.searchService.selectNodes( rootNode , "//.[@" + NamespaceService.CONTENT_MODEL_PREFIX + ":" + FUTURE_NODE_QNAME.getLocalName() + "]" , null , serviceRegistry.getNamespaceService() , false );
		for( NodeRef node : fNodes ) {
			String val = this.nodeService.getProperty( node , FUTURE_NODE_QNAME ).toString();
			logger.log( Level.DEBUG , "Processing \"" + val + "\"." );
			String[] chunks = val.split( "|" );
			String path = chunks[0];
			String originClass = chunks[1];
			String nsUri = chunks[2];
			String ln = chunks[3];
			QName propertyQName = QName.createQName( nsUri , ln );
			logger.log( Level.DEBUG , "Path: \"" + path + "\"." );
			ArrayList<NodeRef> nodeRefs = alfContent.getNodesFromDisplayPath( path );
			if( nodeRefs.size() > 0 ){
				NodeRef nodeRef = ( nodeRefs.size() > 0 ) ? nodeRefs.get( nodeRefs.size() - 1 ) : null;
				if( originClass.equals( NodeRef.class.getName() ) ){
					this.nodeService.setProperty( node , propertyQName , nodeRef );
				}
				else if ( originClass.equals( PRIMITIVE_ASSOC ) ) {
					this.nodeService.setProperty( node , propertyQName , nodeRef.toString() );
				}
				else {
					logger.log( Level.WARN ,  "None of expected future node types (" + val + ")." );
				}
			}
			else{
				// can not resolve it
				logger.log( Level.WARN , "Still can not resolve future node \"" + path + "\"." );
			}
		}
	}
}
