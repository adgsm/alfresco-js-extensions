package rs.make.alfresco.alfcontent;

import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.alfresco.repo.jscript.BaseScopableProcessorExtension;
import org.alfresco.model.ContentModel;
//import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.version.Version;
import org.alfresco.service.cmr.version.VersionService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;

import org.apache.log4j.Logger;

import rs.make.alfresco.jsunzip.JSUnzip;

public class AlfContent  extends BaseScopableProcessorExtension{
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

    private static Logger logger = Logger.getLogger( JSUnzip.class );

    private final String COMPANY_HOME_NAME = "Company Home";
    private final QName COMPANY_HOME_QNAME = QName.createQName( NamespaceService.APP_MODEL_1_0_URI, "company_home" );
    private NodeRef companyHome = null;
    
    private List<StoreRef> stores;
    private List<NodeRef> rootNodes;

    private List<StoreRef> getStores(){
    	return this.nodeService.getStores();
    }

    private List<NodeRef> getRootNodes( List<StoreRef> stores ){
    	List<NodeRef> rootNodesList = new ArrayList<NodeRef>();
    	for( StoreRef store : stores ){
    		NodeRef nodeRef = this.nodeService.getRootNode( store );
    		rootNodesList.add( nodeRef );
    	}
    	return rootNodesList;
    }
    
    private NodeRef resolveCompanyHome( String name , NodeRef rootNode ){
    	if( companyHome == null ){
    		List<ChildAssociationRef> companyHomeAssocRefs = nodeService.getChildAssocs( rootNode , ContentModel.ASSOC_CHILDREN , COMPANY_HOME_QNAME );
    		if( companyHomeAssocRefs.size() == 1 ){
    			companyHome = companyHomeAssocRefs.get(0).getChildRef();
    		}
    	}
    	if( name.equals( COMPANY_HOME_NAME ) ) return companyHome;
    	return null;
    }
    
    private void checkStoresAndRootNodes(){
    	this.stores = getStores();
    	this.rootNodes = getRootNodes( this.stores );
    }

	private QName getQNameType( NodeRef nodeRef )
    {
		QName type = null;
		if( nodeRef != null ){
			type = nodeService.getType( nodeRef );
		}
		return type;
    }

	public boolean isContainer( NodeRef nodeRef )
    {
		Boolean isContainer = Boolean.valueOf( ( dictionaryService.isSubClass( getQNameType( nodeRef ), ContentModel.TYPE_FOLDER ) == true &&
            		dictionaryService.isSubClass( getQNameType( nodeRef ) , ContentModel.TYPE_SYSTEM_FOLDER ) == false ) );
        
        return isContainer.booleanValue();
    }

	public NodeRef createContentNode( NodeRef parent , String name , InputStream content , String encoding , String mimetype , String model , String type ) throws Exception
	{
		QName qtype = null;
		if( parent == null || name.equals( "" ) || content == null ) return null;
//		if( encoding == null || encoding.equals( "" ) ) encoding = "UTF-8";
//		if( mimetype == null || mimetype.equals( "" ) ) mimetype = MimetypeMap.MIMETYPE_TEXT_PLAIN;
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
			Map<QName, Serializable> properties = this.nodeService.getProperties( child );
			this.versionService.ensureVersioningEnabled( child, properties );
			Version childVersion = this.versionService.createVersion( child , null );
			node = childVersion.getVersionedNodeRef();
			logger.info( "New version of \"" + name + "\" content node created. " );
		}
		else{
		    Map<QName, Serializable> props = new HashMap<QName, Serializable>(1);
		    props.put( ContentModel.PROP_NAME , name );  
		    node = this.nodeService.createNode( parent , assoc , QName.createQName( model , name ) , qtype , props ).getChildRef();                        
			logger.info( "New \"" + name + "\" content node created. " );
		}

	    ContentWriter writer = serviceRegistry.getContentService().getWriter( node, ContentModel.PROP_CONTENT, true );
	    if( mimetype != null ) writer.setMimetype( mimetype );
	    if( encoding != null ) writer.setEncoding( encoding );
	    writer.putContent( content );

	    return node;
	}

	public NodeRef createFolderNode( NodeRef parent , String name , String model , String type )
	{
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
			companyHomeCheck = resolveCompanyHome( name , parent );
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
				logger.info( "Using existing container \"" + name + "\"." );
			}
			else if( children.size() == 0 ){
			    Map<QName, Serializable> props = new HashMap<QName, Serializable>(1);
			    props.put( ContentModel.PROP_NAME , name );  
			    node = this.nodeService.createNode( parent , assoc , QName.createQName( model , name ) , qtype , props ).getChildRef();                        
				logger.info( "Creating new container \"" + name + "\". " );
			}
			else {
				logger.info( "There is more than one container named \"" + name + "\" existing... Do not know which one to use. Giving up." );
				return null;
			}
		}
		else{
			logger.info( "Using existing Company Home." );
			node = companyHomeCheck;
		}

	    return node;
	}
}
