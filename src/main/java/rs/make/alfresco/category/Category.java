package rs.make.alfresco.category;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.jscript.BaseScopableProcessorExtension;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.Path;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.CategoryService;
import org.alfresco.service.cmr.search.CategoryService.Depth;
import org.alfresco.service.cmr.search.CategoryService.Mode;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;
import org.springframework.http.MediaType;

import rs.make.alfresco.alfcontent.AlfContent;

public class Category extends BaseScopableProcessorExtension {
	private NodeService nodeService;
	public NodeService getNodeService() {
		return nodeService;
	}
	public void setNodeService( NodeService nodeService ) {
		this.nodeService = nodeService;
	}

	private CategoryService categoryService;
	public CategoryService getCategoryService() {
		return categoryService;
	}
	public void setCategoryService( CategoryService categoryService ) {
		this.categoryService = categoryService;
	}

	protected ContentService contentService;
	public ContentService getContentService() {
		return contentService;
	}
	public void setContentService( ContentService contentService ) {
		this.contentService = contentService;
	}

	protected AlfContent alfContent;
	public AlfContent getAlfContent() {
		return alfContent;
	}
	public void setAlfContent( AlfContent alfContent ) {
		this.alfContent = alfContent;
	}

	private static Logger logger = Logger.getLogger( Category.class );

	private static final String NAME_PREFIX = "__";
	private static final String NAME_KEY = "_name";
	private static final String TYPE_KEY = "_type";
	private static final String ASSOC_KEY = "_assoc";
	private static final String NAMESPACE_URI_KEY = "_namespace-uri";
	private static final String LOCAL_NAME_KEY = "_local-name";
	private static final String PATH_KEY = "_path";

	private final QName CATEGORY_ROOT_ASSOC_QNAME = QName.createQName( NamespaceService.CONTENT_MODEL_1_0_URI, "categoryRoot" );

	private final static String TOKEN_SEPARATOR = "\\A";

	Collection<ChildAssociationRef> categories = new ArrayList<ChildAssociationRef>();

	private ObjectMapper objectMapper = new ObjectMapper();
	ObjectNode jsonExport = objectMapper.createObjectNode();
	
	public String json = "";

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

	public void importCategories( String sJsonNodeRef ) throws Exception{
		if( sJsonNodeRef == null || !AlfContent.isNodeRef( sJsonNodeRef ) ){
			throw new Exception( "Reference \"" + sJsonNodeRef + "\" provided for JSON import file is not a valid nodeRef." );
		}
		NodeRef jsonNodeRef = new NodeRef( sJsonNodeRef );
		ContentReader categoriesContentReader = this.contentService.getReader( jsonNodeRef , ContentModel.PROP_CONTENT );
		String jsonmimetype = categoriesContentReader.getMimetype();
		if( jsonmimetype.equalsIgnoreCase( MediaType.APPLICATION_JSON_VALUE ) == false ){
			throw new Exception( "Invalid mimetype of categories json import node: \"" + jsonmimetype + "\". It should be \"" + MediaType.APPLICATION_JSON_VALUE + "\"." );
		}
		InputStream isprop = categoriesContentReader.getContentInputStream();
		JsonNode categories = null;
		try{
			String json = inputStreamToString( isprop );
			categories = objectMapper.readTree( json );
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
		finally{
			isprop.close();
		}
		if( categories == null || !categories.isObject() ){
			throw new Exception( "Could not read categories data out of provided JSON node." );
		}
		iterateJson( categories , null );
	}
	
	private void iterateJson( JsonNode jsonNode , NodeRef parentNodeRef ){
		if( jsonNode == null ) return;
		NodeRef rootNode = this.nodeService.getRootNode( StoreRef.STORE_REF_WORKSPACE_SPACESSTORE );
		if( parentNodeRef == null ) parentNodeRef = rootNode;
		NodeRef categoryRoot = null;
		List<ChildAssociationRef> categoryRootAssocRefs = this.nodeService.getChildAssocs( rootNode , ContentModel.ASSOC_CHILDREN , CATEGORY_ROOT_ASSOC_QNAME );
		if( categoryRootAssocRefs.size() == 1 ){
			categoryRoot = categoryRootAssocRefs.get(0).getChildRef();
		}
		Iterator<String> nodeNames = jsonNode.getFieldNames();
		Collection<JsonNode> children = new ArrayList<JsonNode>();
		String name = null;
		QName type = null;
		QName assoc = null;
		while( nodeNames.hasNext() ){
			String nodeName = nodeNames.next();
			JsonNode child = jsonNode.get( nodeName );
			if( nodeName.equals( NAME_KEY ) ){
				name = child.getTextValue();
			}
			else if( nodeName.equals( TYPE_KEY ) ){
				JsonNode nsuri = child.get( NAMESPACE_URI_KEY );
				JsonNode ln = child.get( LOCAL_NAME_KEY );
				if( nsuri != null && nsuri.getTextValue() != null && ln != null && ln.getTextValue() != null ){
					type = QName.createQName( nsuri.getTextValue() , ln.getTextValue() );
				}
			}
			else if( nodeName.equals( ASSOC_KEY ) ){
				JsonNode nsuri = child.get( NAMESPACE_URI_KEY );
				JsonNode ln = child.get( LOCAL_NAME_KEY );
				if( nsuri != null && nsuri.getTextValue() != null && ln != null && ln.getTextValue() != null ){
					assoc = QName.createQName( nsuri.getTextValue() , ln.getTextValue() );
				}
			}
			else if( nodeName.equals( PATH_KEY ) ){
				// do nothing
			}
			else{
				children.add( child );
			}
		}
		if( assoc == null ){
			for( JsonNode child : children ){
				iterateJson( child , parentNodeRef );
			}
		}
		else{
			List<ChildAssociationRef> nextChildAssocs = this.nodeService.getChildAssocs( parentNodeRef , ( parentNodeRef.equals( rootNode ) ) ? ContentModel.ASSOC_CHILDREN : ( parentNodeRef.equals( categoryRoot ) ) ? ContentModel.ASSOC_CATEGORIES : ContentModel.ASSOC_SUBCATEGORIES , assoc );
			if( nextChildAssocs.size() == 1 ){
				NodeRef nextChildNodeRef = nextChildAssocs.get(0).getChildRef();
				logger.info( "Using existing category \"" + this.nodeService.getProperty( nextChildNodeRef , ContentModel.PROP_NAME ).toString() + "\"." );
				for( JsonNode child : children ){
					iterateJson( child , nextChildNodeRef );
				}
			}
			else if( nextChildAssocs.size() > 1 ){
				for( ChildAssociationRef nextChildAssoc : nextChildAssocs ){
					NodeRef nextChildNodeRef = nextChildAssoc.getChildRef();
					logger.warn( "Houston we have a problem! I found more than one child: \"" + this.nodeService.getProperty( nextChildNodeRef , ContentModel.PROP_NAME ).toString() + "\" under \"" + this.nodeService.getProperty( parentNodeRef , ContentModel.PROP_NAME ).toString() + "\"." );
				}
			}
			else {
				logger.info( "Creating new category \"" + assoc.toString() + "( " + type.toString() + " )\", \"" + type.toString() + "\" under \"" + this.nodeService.getProperty( parentNodeRef , ContentModel.PROP_NAME ).toString() + "\"." );
				HashMap<QName, Serializable> props = new HashMap<QName, Serializable>(1);
				props.put( ContentModel.PROP_NAME, name );
				ChildAssociationRef nextChildAssoc = this.nodeService.createNode( parentNodeRef , ( parentNodeRef.equals( rootNode ) ) ? ContentModel.ASSOC_CHILDREN : ( parentNodeRef.equals( categoryRoot ) ) ? ContentModel.ASSOC_CATEGORIES : ContentModel.ASSOC_SUBCATEGORIES , assoc , type , props );
				NodeRef nextChildNodeRef = nextChildAssoc.getChildRef();
				for( JsonNode child : children ){
					iterateJson( child , nextChildNodeRef );
				}
			}
		}
	}

	public NodeRef exportCategories( String sContainerNodeRef , String alfNodeName ) throws Exception {
		if( sContainerNodeRef == null || !AlfContent.isNodeRef( sContainerNodeRef ) ){
			throw new Exception( "Reference \"" + sContainerNodeRef + "\" provided for JSON file container is not a valid nodeRef." );
		}
		NodeRef containerNodeRef = new NodeRef( sContainerNodeRef );
		if( !this.alfContent.isContainer( containerNodeRef ) ){
			throw new Exception( "Reference \"" + sContainerNodeRef + "\" provided for JSON file container is not a folder." );
		}
		if( alfNodeName.isEmpty() ){
			throw new Exception( "\"" + alfNodeName + "\" is not valid file name." );
		}
		if( !alfNodeName.endsWith( ".json" ) ){
			alfNodeName += ".json";
		}

		getCategories();
		for( ChildAssociationRef category : categories ){
			ObjectNode helper = this.jsonExport;
			NodeRef categoryNodeRef = category.getChildRef();
			Collection<Map<NodeRef,QName>> nodeObjs = new ArrayList<Map<NodeRef,QName>>();
			NodeRef parentNodeRef = categoryNodeRef;
			while( parentNodeRef != null ){
				ChildAssociationRef parentAssoc = this.nodeService.getPrimaryParent( parentNodeRef );
				Map<NodeRef,QName> categoryObj = new HashMap<NodeRef,QName>(1);
				categoryObj.put( parentNodeRef, parentAssoc.getQName() );
				nodeObjs.add( categoryObj );
				parentNodeRef = parentAssoc.getParentRef();
			}
			Collections.reverse( ( ArrayList<Map<NodeRef,QName>> ) nodeObjs );
			for( Map<NodeRef,QName> nodeObj : nodeObjs ){
				NodeRef nodeRef = nodeObj.keySet().iterator().next();
				QName assoc = nodeObj.get( nodeRef );
				String nodeName = this.nodeService.getProperty( nodeRef , ContentModel.PROP_NAME ).toString();
				if( !helper.has( nodeName ) ){
					ObjectNode chunkNode = helper.with( NAME_PREFIX + nodeName );
					chunkNode.put( NAME_KEY, nodeName );
					ObjectNode chunkNodeType = chunkNode.with( TYPE_KEY );
					QName categoryNodeType = this.nodeService.getType( nodeRef );
					String nodeTypeNameSpaceURI = categoryNodeType.getNamespaceURI();
					String nodeTypeLocalName = categoryNodeType.getLocalName();
					chunkNodeType.put( NAMESPACE_URI_KEY, nodeTypeNameSpaceURI );
					chunkNodeType.put( LOCAL_NAME_KEY, nodeTypeLocalName );
					ObjectNode chunkNodeAssoc = chunkNode.with( ASSOC_KEY );
					chunkNodeAssoc.put( NAMESPACE_URI_KEY, ( assoc != null ) ? assoc.getNamespaceURI() : null );
					chunkNodeAssoc.put( LOCAL_NAME_KEY, ( assoc != null ) ? assoc.getLocalName() : null );
					Path categoryNodePath = this.nodeService.getPath( nodeRef );
					chunkNode.put( PATH_KEY , categoryNodePath.toString() );
					helper = chunkNode;
				}
			}
		}
		json = objectMapper.writeValueAsString( this.jsonExport );
		InputStream jsonis = new ByteArrayInputStream( json.getBytes( StandardCharsets.UTF_8.name() ) );
		NodeRef jsonNodeRef = this.alfContent.createContentNode( containerNodeRef , alfNodeName , jsonis , StandardCharsets.UTF_8.name() , MediaType.APPLICATION_JSON_VALUE , null , null );
		jsonis.close();
		return jsonNodeRef;
	}

	public String[] get() {
		Collection<String> nodeRefs = new ArrayList<String>();
		getCategories();
		for( ChildAssociationRef category : categories ){
			NodeRef categoryNodeRef = category.getChildRef();
			nodeRefs.add( categoryNodeRef.toString() );
		}
		String[] nodeRefStrings = new String[ nodeRefs.size() ];
		return nodeRefs.toArray( nodeRefStrings );
	}

	private void getCategories() {
		Collection<ChildAssociationRef> rootCategories = getRootCategories();
		for( ChildAssociationRef rootCategory : rootCategories ){
			categories.add( rootCategory );
			getSubCategories( rootCategory );
		}
	}

	private void getSubCategories( ChildAssociationRef parentCategory ) {
		NodeRef parentCategoryNodeRef = parentCategory.getChildRef();
		Collection<ChildAssociationRef> parentCategoryChildren = this.categoryService.getChildren( parentCategoryNodeRef, Mode.SUB_CATEGORIES , Depth.IMMEDIATE );
		if( !parentCategoryChildren.isEmpty() ){
			for( ChildAssociationRef parentCategoryChild : parentCategoryChildren ){
				if( !categories.contains( parentCategoryChild ) ) categories.add( parentCategoryChild );
				getSubCategories( parentCategoryChild );
			}
		}
	}

	private Collection<ChildAssociationRef> getRootCategories() {
		Collection<ChildAssociationRef> allRootCategories = new ArrayList<ChildAssociationRef>();
		Collection<QName> classificationAspects = this.categoryService.getClassificationAspects();
		for( QName classificationAspect : classificationAspects ){
			Collection<ChildAssociationRef> rootCategories = this.categoryService.getRootCategories( StoreRef.STORE_REF_WORKSPACE_SPACESSTORE , classificationAspect );
			allRootCategories.addAll( rootCategories );
		}
		return allRootCategories;
	}
}
