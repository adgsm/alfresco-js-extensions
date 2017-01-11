package rs.make.alfresco.alfcontent;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.alfresco.repo.jscript.BaseScopableProcessorExtension;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.site.SiteModel;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.repo.version.VersionModel;
import org.alfresco.model.ContentModel;
//import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.InvalidNodeRefException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.MutableAuthenticationService;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.cmr.site.SiteInfo;
//import org.alfresco.service.cmr.site.SiteRole;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.cmr.version.Version;
import org.alfresco.service.cmr.version.VersionService;
import org.alfresco.service.cmr.version.VersionType;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.apache.http.Header;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.springframework.extensions.surf.util.URLDecoder;

import rs.make.alfresco.globalproperties.GlobalProperties;
import rs.make.alfresco.request.Request;
import rs.make.alfresco.request.Request.Response;

public class AlfContent extends BaseScopableProcessorExtension {
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

	protected VersionService versionService;
	public VersionService getVersionService() {
		return versionService;
	}
	public void setVersionService( VersionService versionService ) {
		this.versionService = versionService;
	}

	protected DictionaryService dictionaryService;
	public DictionaryService getDictionaryService() {
		return dictionaryService;
	}
	public void setDictionaryService( DictionaryService dictionaryService ) {
		this.dictionaryService = dictionaryService;
	}

	protected TransactionService transactionService;
	public TransactionService getTransactionService() {
		return transactionService;
	}
	public void setTransactionService( TransactionService transactionService ) {
		this.transactionService = transactionService;
	}

	protected MutableAuthenticationService authenticationService;
	public MutableAuthenticationService getAuthenticationService() {
		return authenticationService;
	}
	public void setAuthenticationService( MutableAuthenticationService authenticationService ) {
		this.authenticationService = authenticationService;
	}

	protected PersonService personService;
	public PersonService getPersonService() {
		return personService;
	}
	public void setPersonService( PersonService personService ) {
		this.personService = personService;
	}

	protected SiteService siteService;
	public SiteService getSiteService() {
		return siteService;
	}
	public void setSiteService( SiteService siteService ) {
		this.siteService = siteService;
	}

	protected AuthorityService authorityService;
	public AuthorityService getAuthorityService() {
		return authorityService;
	}
	public void setAuthorityService( AuthorityService authorityService ) {
		this.authorityService = authorityService;
	}

	protected Request request;
	public Request getRequest() {
		return request;
	}
	public void setRequest( Request request ) {
		this.request = request;
	}

	private Properties alfProperties;

	private class GP extends GlobalProperties{
		public GP() throws Exception{
			alfProperties = this.load();
		}
	}

	private static Logger logger = Logger.getLogger( AlfContent.class );

	private final int MAX_TRANSACTION_RETRIES = 20;
	private final String MIME_JSON = "application/json";
	private final String DEFAULT_ENCODING = "UTF-8";

	private static final Pattern nodeRefPattern = Pattern.compile( ".+://.+/.+" );
	private final String COMPANY_HOME_NAME = "Company Home";
	private final QName COMPANY_HOME_ASSOC_QNAME = QName.createQName( NamespaceService.APP_MODEL_1_0_URI, "company_home" );
	private final String CATEGORY_ROOT_NAME = "categories";
	private final QName CATEGORY_ROOT_ASSOC_QNAME = QName.createQName( NamespaceService.CONTENT_MODEL_1_0_URI, "categoryRoot" );
	private NodeRef companyHome = null;
	private NodeRef categoryRoot = null;

	private final String DEFAULT_SITE_PRESET = "site-dashboard";
	private final String DEFAULT_SHARE_PROTOCOL = "http";
	private final String DEFAULT_SHARE_HOST = "127.0.0.1";
	private final String DEFAULT_SHARE_PORT = "8080";
	private final String DEFAULT_SHARE_CONTEXT = "share";
	private final String DO_LOGIN = "/page/dologin";
	private final String REPOSITORY = "/page/repository";
	private final String CREATE_SITE = "/page/modules/create-site";

	public List<StoreRef> stores;
	public List<NodeRef> rootNodes;

	public Map<String,ArrayList<NodeRef>> displayPaths = new HashMap<String,ArrayList<NodeRef>>();

	private List<StoreRef> getStores(){
		return this.nodeService.getStores();
	}

	public List<NodeRef> getRootNodes( List<StoreRef> stores ){
		List<NodeRef> rootNodesList = new ArrayList<NodeRef>();
		for( StoreRef store : stores ){
			NodeRef nodeRef = this.nodeService.getRootNode( store );
			rootNodesList.add( nodeRef );
		}
		return rootNodesList;
	}

	private NodeRef resolveRootChildren( String name , NodeRef rootNode ) {
		//NodeRef rootNode = nodeService.getRootNode( StoreRef.STORE_REF_WORKSPACE_SPACESSTORE );
		if( name.equals( COMPANY_HOME_NAME ) ){
			if( companyHome == null ){
				List<ChildAssociationRef> companyHomeAssocRefs = nodeService.getChildAssocs( rootNode , ContentModel.ASSOC_CHILDREN , COMPANY_HOME_ASSOC_QNAME );
				if( companyHomeAssocRefs.size() == 1 ){
					companyHome = companyHomeAssocRefs.get(0).getChildRef();
				}
			}
			return companyHome;
		}
		else if( name.equals( CATEGORY_ROOT_NAME ) ) {
			if( categoryRoot == null ){
				List<ChildAssociationRef> categoryRootAssocRefs = nodeService.getChildAssocs( rootNode , ContentModel.ASSOC_CHILDREN , CATEGORY_ROOT_ASSOC_QNAME );
				if( categoryRootAssocRefs.size() == 1 ){
					categoryRoot = categoryRootAssocRefs.get(0).getChildRef();
				}
			}
			return categoryRoot;
		}
		return null;
	}

	public void checkStoresAndRootNodes() {
		this.stores = getStores();
		this.rootNodes = getRootNodes( this.stores );
	}

	private QName getQNameType( NodeRef nodeRef ) {
		QName type = null;
		if( nodeRef != null ){
			type = nodeService.getType( nodeRef );
		}
		return type;
	}

	public static boolean isNodeRef( String sNodeRef ) {
		Matcher matcher = nodeRefPattern.matcher( sNodeRef );
		return matcher.matches();
	}

	public boolean isContainer( NodeRef nodeRef ) {
		Boolean isContainer = Boolean.valueOf( ( dictionaryService.isSubClass( getQNameType( nodeRef ), ContentModel.TYPE_FOLDER ) == true &&
			dictionaryService.isSubClass( getQNameType( nodeRef ) , ContentModel.TYPE_SYSTEM_FOLDER ) == false ) );

		return isContainer.booleanValue();
	}

	public boolean isSite( NodeRef nodeRef ) {
		boolean hasPrimaryParent = false;
		NodeRef primaryParent = null;
		try{
			primaryParent = nodeService.getPrimaryParent( nodeRef ).getParentRef();
			hasPrimaryParent = true;
		}
		catch( InvalidNodeRefException e ){
			logger.error( e );
			logger.error( e.getCause().toString() );
		}
		Boolean isSite = Boolean.valueOf( dictionaryService.isSubClass( getQNameType( nodeRef ), SiteModel.TYPE_SITE ) == true  &&
				hasPrimaryParent && primaryParent.equals( siteService.getSiteRoot() ) );

		return isSite.booleanValue();
	}

	// TODO provide multiple paths response in case of children with same names
	public ArrayList<NodeRef> getNodesFromDisplayPath( String path ){
		if( path == null || path.equals( "" ) ) return null;
		if( path.startsWith( "/" ) ) path = path.replaceFirst( "/" ,  "" );
		ArrayList<NodeRef> nodeRefs = new ArrayList<NodeRef>();

		if( displayPaths.containsKey( path ) ) {
			logger.log( Level.DEBUG , "Using cached displayPath \"" + path + "\"." );
			nodeRefs = displayPaths.get( path );
		}
		else{
			checkStoresAndRootNodes();
			ArrayList<NodeRef> parentNodes = (ArrayList<NodeRef>) this.rootNodes;
			String[] pathChunks = path.split( "/" );
			ArrayList<String> dPath = new ArrayList<String>();
			String diPath = "";
			pathChunks_loop:
			for( String pathChunk : pathChunks ){
				dPath.add( pathChunk );
				//diPath = String.join( "/" , dPath ); // Java 8
				StringBuilder sb = new StringBuilder();
				for ( String s : dPath ) {
					sb.append( s ).append( "/" );
				}
				sb.deleteCharAt( sb.length() - 1 );
				diPath = sb.toString();
				if( displayPaths.containsKey( diPath ) ) {
					ArrayList<NodeRef> cachedNodeRefs = new ArrayList<NodeRef>();
					cachedNodeRefs = displayPaths.get( diPath );
					nodeRefs.clear();
					nodeRefs.addAll( cachedNodeRefs );
					cachedNodeRefs = null;
					logger.log( Level.DEBUG , "Using cached displayPath \"" + diPath + "\". Path: \"" + this.nodeService.getPath( nodeRefs.get( nodeRefs.size() - 1 ) ).toString() + "\"." );
				}
				else{
					boolean match = false;
					for( NodeRef parentNode : parentNodes ){
						List<ChildAssociationRef> childrenAssocs = this.nodeService.getChildAssocs( parentNode );
						childrenAssocs_loop:
						for( ChildAssociationRef childrenAssoc : childrenAssocs ){
							NodeRef child = childrenAssoc.getChildRef();
							String nodeName = this.nodeService.getProperty( child , ContentModel.PROP_NAME ).toString();
							if( pathChunk.equals( nodeName ) ){
								match = true;
								nodeRefs.add( child );
								break childrenAssocs_loop;
							}
						}
					}
					if( match == false ) {
						logger.warn( "Can not find provided path \"" + path + "\"." );
						nodeRefs.clear();
						break pathChunks_loop;
					}
					else{
						ArrayList<NodeRef> clonedNodeRefs = new ArrayList<NodeRef>();
						clonedNodeRefs.addAll( nodeRefs );
						displayPaths.put( diPath , clonedNodeRefs );
						logger.log( Level.DEBUG , "Added \"" + diPath + "\" displayPath to cache. Path: \"" + this.nodeService.getPath( clonedNodeRefs.get( clonedNodeRefs.size() - 1 ) ).toString() + "\"." );
						clonedNodeRefs = null;
					}
				}
				parentNodes.clear();
				if( nodeRefs != null && !nodeRefs.isEmpty() ) parentNodes.add( nodeRefs.get( nodeRefs.size() - 1 ) );
			}
		}
		return nodeRefs;
	}

	public NodeRef createContentNode( NodeRef parent , String name , InputStream content , String encoding , String mimetype ) throws Exception {
		return createContentNode( parent , name , content , encoding , mimetype , null , null );
	}

	public NodeRef createContentNode( NodeRef parent , String name , InputStream content , String encoding , String mimetype , boolean doInTransaction ) throws Exception {
		return createContentNode( parent , name , content , encoding , mimetype , null , null , doInTransaction );
	}

	public NodeRef createContentNode( NodeRef parent , String name , InputStream content , String encoding , String mimetype , String model , String type ) throws Exception {
		return createContentNode( parent , name , content , encoding , mimetype , model , type , null );
	}

	public NodeRef createContentNode( NodeRef parent , String name , InputStream content , String encoding , String mimetype , String model , String type , boolean doInTransaction ) throws Exception {
		return createContentNode( parent , name , content , encoding , mimetype , model , type , null , doInTransaction );
	}

	public NodeRef createContentNode( NodeRef parent , String name , InputStream content , String encoding , String mimetype , String model , String type , Map<QName, Serializable> properties ) throws Exception {
		return createContentNode( parent , name , content , encoding , mimetype , model , type , properties , true );
	}

	public NodeRef createContentNode( NodeRef parent , String name , InputStream content , String encoding , String mimetype , String model , String type , Map<QName, Serializable> properties , boolean doInTransaction ) throws Exception {
		QName qtype = null;
		if( parent == null || name.equals( "" ) || content == null ) return null;
		//if( encoding == null || encoding.equals( "" ) ) encoding = "UTF-8";
		//if( mimetype == null || mimetype.equals( "" ) ) mimetype = MimetypeMap.MIMETYPE_TEXT_PLAIN;
		if( model == null || model == "" ) model = NamespaceService.CONTENT_MODEL_1_0_URI;
		if( type == null || type == "" ) {
			qtype = ContentModel.TYPE_CONTENT;
		}
		else{
			qtype = QName.createQName( model , type );
		}
		NodeRef node = null;

		checkStoresAndRootNodes();
		QName assoc = ( this.rootNodes.indexOf( parent ) > -1 ) ? ContentModel.ASSOC_CHILDREN : ContentModel.ASSOC_CONTAINS;

		List<String> names = Arrays.asList( name );
		List<ChildAssociationRef> children = this.nodeService.getChildrenByName( parent , assoc , names );
		if( children.size() == 1 ){
			NodeRef child = children.get(0).getChildRef();
			Map<QName, Serializable> existingProperties = this.nodeService.getProperties( child );
			if( properties != null ) existingProperties.putAll( properties );
			node = createNodeVersion( child , content , encoding , mimetype , properties );
//			nodeService.setProperties( node , existingProperties );
			logger.log( Level.DEBUG , "New version of \"" + name + "\" content node created. " );
		}
		else{
			if( properties == null ) properties = new HashMap<QName, Serializable>(1);
			properties.put( ContentModel.PROP_NAME , name );
			if( doInTransaction ){
				node = createNodeInTransaction( parent , assoc , model , name , qtype , properties , content , encoding , mimetype );
			}
			else{
				node = createNode( parent , assoc , model , name , qtype , properties , content , encoding , mimetype );
			}
			logger.log( Level.DEBUG , "New \"" + name + "\" content node created. " );
		}

		return node;
	}

	public NodeRef createFolderNode( NodeRef parent , String name ) throws Exception {
		return createFolderNode( parent , name , null , null );
	}

	public NodeRef createFolderNode( NodeRef parent , String name , boolean doInTransaction ) throws Exception {
		return createFolderNode( parent , name , null , null , doInTransaction );
	}

	public NodeRef createFolderNode( NodeRef parent , String name , String model , String type ) throws Exception {
		return createFolderNode( parent , name , model , type , null , true );
	}

	public NodeRef createFolderNode( NodeRef parent , String name , String model , String type , boolean doInTransaction ) throws Exception {
		return createFolderNode( parent , name , model , type , null , doInTransaction );
	}

	public NodeRef createFolderNode( NodeRef parent , String name , String model , String type , Map<QName, Serializable> properties ) throws Exception {
		return createFolderNode( parent , name , model , type , properties , true );
	}

	public NodeRef createFolderNode( NodeRef parent , String name , String model , String type , Map<QName, Serializable> properties , boolean doInTransaction ) throws Exception {
		QName qtype = null;
		if( parent == null || name.equals( "" ) ) return null;
		if( model == null || model.equals( "" ) ) model = NamespaceService.CONTENT_MODEL_1_0_URI;
		if( type == null || type.equals( "" ) ) {
			qtype = ContentModel.TYPE_FOLDER;
		}
		else{
			qtype = QName.createQName( model , type );
		}
		NodeRef node = null;

		checkStoresAndRootNodes();
		NodeRef companyHomeCheck = null;
		QName assoc = null;
		if( this.rootNodes.indexOf( parent ) > -1 ){
			companyHomeCheck = resolveRootChildren( name , parent );
			assoc = ContentModel.ASSOC_CHILDREN;
		}
		else {
			assoc = ContentModel.ASSOC_CONTAINS;
		}
		if( companyHomeCheck == null ){
			List<String> names = Arrays.asList( name );
			List<ChildAssociationRef> children = this.nodeService.getChildrenByName( parent , assoc , names );
			if( children.size() == 1 ){
				node = children.get(0).getChildRef();
				Map<QName, Serializable> existingProperties = this.nodeService.getProperties( node );
				if( properties != null ) existingProperties.putAll( properties );
				nodeService.addProperties( node , existingProperties );
				logger.log( Level.DEBUG , "Using existing container \"" + name + "\"." );
			}
			else if( children.size() == 0 ){
				if( properties == null ) properties = new HashMap<QName, Serializable>(1);
				properties.put( ContentModel.PROP_NAME , name );
				if( doInTransaction ){
					node = createNodeInTransaction( parent , assoc , model , name , qtype , properties , null , null , null );
				}
				else{
					node = createNode( parent , assoc , model , name , qtype , properties , null , null , null );
				}
				logger.log( Level.DEBUG , "Creating new container \"" + name + "\". " );
			}
			else {
				logger.log( Level.WARN , "There is more than one container named \"" + name + "\" existing... Do not know which one to use. Giving up." );
				return null;
			}
		}
		else{
			logger.log( Level.DEBUG , "Using existing Company Home." );
			node = companyHomeCheck;
		}

		return node;
	}

	private NodeRef createNodeVersion( NodeRef node , InputStream content , String encoding , String mimetype , Map<QName, Serializable> properties ) throws IOException{
		NodeRef version = null;
		Map<QName, Serializable> versionPropertiesQName = new HashMap<QName, Serializable>();
		versionPropertiesQName.put( QName.createQName( VersionModel.NAMESPACE_URI, VersionModel.PROP_VERSION_TYPE ), VersionType.MINOR );
		versionPropertiesQName.put( QName.createQName( VersionModel.NAMESPACE_URI, VersionModel.PROP_DESCRIPTION ), "Code driven update (" + AlfContent.class.getName() + ")." );
		versionService.ensureVersioningEnabled( node , null );
		Map<String, Serializable> versionProperties = new HashMap<String, Serializable>();
		versionProperties.put( VersionModel.PROP_VERSION_TYPE, VersionType.MINOR );
		versionProperties.put( VersionModel.PROP_DESCRIPTION, "Code driven update (" + AlfContent.class.getName() + ")." );
		Version childVersion = versionService.createVersion( node , versionProperties );
		version = childVersion.getVersionedNodeRef();

		ContentWriter writer = contentService.getWriter( version , ContentModel.PROP_CONTENT , true );
		if( mimetype != null ) writer.setMimetype( mimetype );
		if( encoding != null ) writer.setEncoding( encoding );
		writer.putContent( content );
		content.close();

		if( properties != null ){
			nodeService.addProperties( version , properties );
		}

		return version;
	}

	private NodeRef createNode( NodeRef parent , QName assoc , String model , String name , QName qtype , Map<QName, Serializable> properties , InputStream content , String encoding , String mimetype ) throws Exception{
		NodeRef txchild = nodeService.createNode( parent , assoc , QName.createQName( model , name ) , qtype , properties ).getChildRef();
		if( content != null ){
			ContentWriter writer = contentService.getWriter( txchild , ContentModel.PROP_CONTENT , true );
			if( mimetype != null ) writer.setMimetype( mimetype );
			if( encoding != null ) writer.setEncoding( encoding );
			writer.putContent( content );
			content.close();
		}
		return txchild;
	}

	private NodeRef createNodeInTransaction( NodeRef parent , QName assoc , String model , String name , QName qtype , Map<QName, Serializable> properties , InputStream content , String encoding , String mimetype ) throws Exception{
		final NodeRef lParent = parent;
		final QName lAssoc = assoc;
		final String lModel = model;
		final String lName = name;
		final QName lQtype = qtype;
		final Map<QName, Serializable> lProperties = properties;
		final String lMimetype = mimetype;
		final String lEncoding = encoding;
		final InputStream lContent = content;
		RetryingTransactionCallback<NodeRef> txcallback = new RetryingTransactionCallback<NodeRef>() {
			@Override
			public NodeRef execute() throws Throwable {
				return createNode( lParent , lAssoc , lModel , lName , lQtype , lProperties , lContent , lEncoding , lMimetype );
			}
		};
		// run transaction
		try {
			RetryingTransactionHelper txnHelper = transactionService.getRetryingTransactionHelper();
			txnHelper.setMaxRetries( MAX_TRANSACTION_RETRIES );
			return txnHelper.doInTransaction( txcallback , false , true );
		}
		catch ( Throwable e ) {
			logger.error( e );
			logger.error( e.getCause().toString() );
			if( content != null ) content.close();
			throw new Exception( e );
		}
	}

	public NodeRef createSiteNode( String sitePreset , String shortName , String title , String description , String visibility ) throws Exception{
		return createSiteNode( sitePreset , shortName , title , description , visibility , null , null );
	}

	public NodeRef createSiteNode( String sitePreset , String shortName , String title , String description , String visibility , Map<QName, Serializable> properties ) throws Exception{
		return createSiteNode( sitePreset , shortName , title , description , visibility , properties , null );
	}

	@SuppressWarnings({ "unused", "unchecked" })
	public NodeRef createSiteNode( String sitePreset , String shortName , String title , String description , String visibility , Map<QName, Serializable> properties , String uri ) throws Exception{
		NodeRef siteNodeRef = null;
		if( shortName == null ){
			logger.error( "Site short name is mandatory parameter." );
			return null;
		}
		shortName = shortName.replaceAll( "\\s+" ,  "-" );
		// check if site already exist
		if( siteService.hasSite( shortName ) ){
			SiteInfo siteInfo = siteService.getSite( shortName );
			if( siteInfo != null ){
				siteNodeRef = siteInfo.getNodeRef();
				logger.log( Level.DEBUG , "Using existing share site \"" + shortName + "\"." );
				return siteNodeRef;
			}
		}
		if( sitePreset == null ) sitePreset = DEFAULT_SITE_PRESET;
		if( title == null ) title = "";
		if( description == null ) description = "";
		if( visibility == null ) visibility = "PRIVATE";

		JSONObject jsonObj = new JSONObject();
		jsonObj.put( "sitePreset" , sitePreset );
		jsonObj.put( "shortName" , shortName );
		jsonObj.put( "title" , title );
		jsonObj.put( "description" , description );
		jsonObj.put( "visibility" , visibility );
		String json = JSONValue.toJSONString( jsonObj );

		GlobalProperties globalProperties = new GP();
		String sProtocol = alfProperties.getProperty( "share.protocol" );
		sProtocol = ( sProtocol != null ) ? sProtocol : DEFAULT_SHARE_PROTOCOL;
		String sHost = alfProperties.getProperty( "share.host" );
		sHost = ( sHost != null ) ? sHost : DEFAULT_SHARE_HOST;
		String sPort = alfProperties.getProperty( "share.port" );
		sPort = ( sPort != null ) ? sPort : DEFAULT_SHARE_PORT;
		String sContext = alfProperties.getProperty( "share.context" );
		sContext = ( sContext != null ) ? sContext : DEFAULT_SHARE_CONTEXT;

		String sURI = ( uri == null ) ? sProtocol + "://" + sHost + ":" + sPort + "/" + sContext : uri;
		String doLoginURI = sURI + DO_LOGIN;
		String repositoryURI = sURI + REPOSITORY;
		String createSiteURI = sURI + CREATE_SITE;

		String temp = UUID.randomUUID().toString();
		NodeRef tempPerson = createPerson( temp , temp , temp , "temp@make.rs" , temp , temp , false , true , -1 );
		if( tempPerson == null ){
			logger.error( "Can not create temporary login for site creation." );
			return null;
		}

		Map<String,String> loginCredentials = new HashMap<String,String>(2);
		loginCredentials.put( "username" , temp );
		loginCredentials.put( "password" , temp );
		Request request = new Request();

		Response doLoginResponse = null;
		try{
			doLoginResponse = request.post( doLoginURI , loginCredentials );
		}
		catch( Exception e ){
			logger.error( e );
			logger.error( e.getCause().toString() );
			this.personService.deletePerson( tempPerson , true );
			return null;
		}

		ArrayList<String> cookies = new ArrayList<String>();
		ParsedResponse sendRepository = parseResponse( doLoginResponse  , cookies );
		if( sendRepository == null ) {
			this.personService.deletePerson( tempPerson , true );
			return null;
		}

		cookies = sendRepository.getCookies();

		Response repositoryResponse = null;
		try{
			repositoryResponse = request.get( repositoryURI , sendRepository.getPreparedHeaders() );
		}
		catch( Exception e ){
			logger.error( e );
			logger.error( e.getCause().toString() );
			this.personService.deletePerson( tempPerson , true );
			return null;
		}

		ParsedResponse sendCreateSite = parseResponse( repositoryResponse  , cookies );
		if( sendCreateSite == null ) {
			this.personService.deletePerson( tempPerson , true );
			return null;
		}

		cookies = sendCreateSite.getCookies();

		Response createSiteResponse = null;
		try{
			createSiteResponse = request.post( createSiteURI , json , MIME_JSON , DEFAULT_ENCODING , sendCreateSite.getPreparedHeaders() );

			if( createSiteResponse != null && createSiteResponse.getCode() == 200 ) {
				// SiteService will return null within a same thread, even after setting a delay?!
				/*SiteInfo siteInfo = this.siteService.getSite( shortName );
				if( siteInfo != null ) siteNodeRef = siteInfo.getNodeRef();*/
				// workaround
				NodeRef siteRoot = siteService.getSiteRoot();
				List<String> names = Arrays.asList( shortName );
				List<ChildAssociationRef> children = this.nodeService.getChildrenByName( siteRoot , ContentModel.ASSOC_CONTAINS , names );
				if( children.size() == 1 ){
					siteNodeRef = children.get( 0 ).getChildRef();
					if( properties != null ) nodeService.setProperties( siteNodeRef , properties );
				}
				// same as above?!
				//this.siteService.setMembership( shortName , AuthenticationUtil.getFullyAuthenticatedUser() , SiteRole.SiteManager.name() );
				//workaround
				String siteManager = "GROUP_site_"  + shortName + "_SiteManager";
				if( this.authorityService.authorityExists( siteManager ) ){
					this.authorityService.addAuthority( siteManager , AuthenticationUtil.getFullyAuthenticatedUser() );
				}
				else{
					logger.error( "\"GROUP_site_"  + shortName + "_SiteManager\" does not exist." );
				}
				this.personService.deletePerson( tempPerson , true );
			}
			else if( createSiteResponse.getCode() != 200 ){
				throw new Exception( createSiteResponse.getContent() );
			}
		}
		catch( Exception e ){
			logger.error( e );
			logger.error( e.getCause().toString() );
			this.personService.deletePerson( tempPerson , true );
			return null;
		}

		return siteNodeRef;
	}

	private ParsedResponse parseResponse( Response response , ArrayList<String> cookies ){
		ParsedResponse result = new ParsedResponse();
		try{
			if( cookies == null ) cookies = new ArrayList<String>();
			if( ( Integer.toString( response.getCode() ).indexOf( "40" ) > -1 ) ){
				logger.error( response.statusLine );
				return null;
			}

			Header[] headers = response.getHeaders();
			for( Header header : headers ){
				String headerName = header.getName();
				if( headerName.toLowerCase().indexOf( "Set-Cookie".toLowerCase() ) > -1 ){
					cookies.add( header.getValue().split( ";" )[0] );
				}
			}

			Object[] sendHeaders = new Object[ cookies.size() ];
			for( int i = 0; i < cookies.size(); i++ ){
				Map<String,String> sendHeader = new HashMap<String,String>(2);
				String cookie = cookies.get( i );
				String[] customHeader = cookie.split( "=" );
				sendHeader.put( "Cookie" , cookie );
				sendHeader.put( customHeader[0] , URLDecoder.decode( ( customHeader.length >= 2 ) ? customHeader[1] : customHeader[0] ) );
				sendHeaders[ i ] = sendHeader;
			}
			result.setPreparedHeaders( sendHeaders );
			result.setCookies( cookies );
		}
		catch( Exception e ){
			logger.error( e );
			logger.error( e.getCause().toString() );
			return null;
		}
		return result;
	}

	private class ParsedResponse {
		private Object[] preparedHeaders;
		private ArrayList<String> cookies;

		private Object[] getPreparedHeaders() {
			return preparedHeaders;
		}
		private void setPreparedHeaders( Object[] preparedHeaders ) {
			this.preparedHeaders = preparedHeaders;
		}
		private ArrayList<String> getCookies() {
			return cookies;
		}
		private void setCookies( ArrayList<String> cookies ) {
			this.cookies = cookies;
		}
	}

	private NodeRef createPerson( final String userName , final String firstName , final String lastName , final String email , final String company , final String password , final boolean accountLocked , final boolean enabled , final int sizeQuota ){
		NodeRef person = null;
		// run it in transaction as system user
		AuthenticationUtil.setRunAsUserSystem();
		RetryingTransactionCallback<NodeRef> txcallback = new RetryingTransactionCallback<NodeRef>() {
			@Override
			public NodeRef execute() throws Throwable {
				NodeRef person = null;
				HashMap<QName, Serializable> properties = new HashMap<QName, Serializable>();
				properties.put( ContentModel.PROP_USERNAME , userName );
				properties.put( ContentModel.PROP_FIRSTNAME , firstName );
				properties.put( ContentModel.PROP_LASTNAME , lastName );
				properties.put( ContentModel.PROP_EMAIL , email );
				properties.put( ContentModel.PROP_ORGANIZATION ,company );
				properties.put( ContentModel.PROP_PASSWORD , password );
				properties.put( ContentModel.PROP_ACCOUNT_LOCKED , accountLocked );
				properties.put( ContentModel.PROP_ENABLED , enabled );
				properties.put( ContentModel.PROP_SIZE_QUOTA , sizeQuota );

				if( !authenticationService.authenticationExists( userName ) ) {
					authenticationService.createAuthentication( userName , password.toCharArray() );
				}
				if ( !personService.personExists( userName ) ) {
					person = personService.createPerson( properties );
				}
				return person;
			}
		};
		// run transaction
		try {
			RetryingTransactionHelper txnHelper = transactionService.getRetryingTransactionHelper();
			txnHelper.setMaxRetries( MAX_TRANSACTION_RETRIES );
			person = txnHelper.doInTransaction( txcallback , false , true );
		}
		catch ( Throwable e ) {
			logger.error( e );
			logger.error( e.getCause().toString() );
		}
		return person;
	}
}
