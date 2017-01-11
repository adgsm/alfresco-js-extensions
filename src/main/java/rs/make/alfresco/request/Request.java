package rs.make.alfresco.request;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.http.StatusLine;
import org.apache.http.Header;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.HttpMessage;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

import rs.make.alfresco.alfcontent.AlfContent;
import rs.make.alfresco.jsunzip.JSUnzip;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.jscript.BaseScopableProcessorExtension;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.apache.http.entity.mime.MultipartEntityBuilder;

public class Request extends BaseScopableProcessorExtension {
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

	protected AlfContent alfContent;
	public AlfContent getAlfContent() {
		return alfContent;
	}
	public void setAlfContent( AlfContent alfContent ) {
		this.alfContent = alfContent;
	}

	private static Logger logger = Logger.getLogger( JSUnzip.class );

	private static final String DEFAULT_ENCODING = "UTF-8";
	private static final Charset DEFAULT_CHARSET = Consts.UTF_8;
	private static final String DEFAULT_MIMETYPE = "plain/text";
	private static final int DEFAULT_RESPONSE = 404;

	private ResponseHandler<Response> responseHandler = new ResponseHandler<Response>() {
		@Override
		public Response handleResponse( final HttpResponse responseObj ) throws ClientProtocolException, IOException {
			Response response = new Response();
			StatusLine statusLine = responseObj.getStatusLine();
			response.setStatusLine( statusLine );
			int code = ( statusLine != null ) ? statusLine.getStatusCode() : DEFAULT_RESPONSE;
			response.setCode( code );
			Header[] headers = responseObj.getAllHeaders();
			response.setHeaders( headers );
			Locale locale = responseObj.getLocale();
			response.setLocale( locale );
			ProtocolVersion protocolVersion = responseObj.getProtocolVersion();
			response.setProtocolVersion( protocolVersion );
			HttpEntity entity = responseObj.getEntity();
			String content = ( entity != null ) ? EntityUtils.toString( entity ) : null;
			response.setContent( content );
			Header contentEncoding = entity.getContentEncoding();
			response.setContentEncoding( contentEncoding );
			Header contentType = entity.getContentType();
			response.setContentType( contentType );
			Long contentLength = entity.getContentLength();
			response.setContentLength( contentLength );
			return response;
		}
	};

	public class Response {
		public StatusLine statusLine;
		public int code;
		public Header[] headers;
		public Locale locale;
		public ProtocolVersion protocolVersion;
		public String content;
		public Header contentEncoding;
		public Header contentType;
		public Long contentLength;

		public int getCode() {
			return code;
		}
		private void setCode( int code ) {
			this.code = code;
		}
		public StatusLine getStatusLine() {
			return statusLine;
		}
		private void setStatusLine( StatusLine statusLine ) {
			this.statusLine = statusLine;
		}
		public Header[] getHeaders() {
			return headers;
		}
		private void setHeaders( Header[] headers ) {
			this.headers = headers;
		}
		public Locale getLocale() {
			return locale;
		}
		private void setLocale( Locale locale ) {
			this.locale = locale;
		}
		public ProtocolVersion getProtocolVersion() {
			return protocolVersion;
		}
		private void setProtocolVersion( ProtocolVersion protocolVersion ) {
			this.protocolVersion = protocolVersion;
		}
		public String getContent() {
			return content;
		}
		private void setContent( String content ) {
			this.content = content;
		}
		public Header getContentEncoding() {
			return contentEncoding;
		}
		private void setContentEncoding( Header contentEncoding ) {
			this.contentEncoding = contentEncoding;
		}
		public Header getContentType() {
			return contentType;
		}
		private void setContentType( Header contentType ) {
			this.contentType = contentType;
		}
		public Long getContentLength() {
			return contentLength;
		}
		private void setContentLength( Long contentLength ) {
			this.contentLength = contentLength;
		}
	}

	private List<NameValuePair> packParameters( Map<String,String> parameters , List<NameValuePair> formParams ){
		if( parameters == null ) parameters = new HashMap<String,String>(0);
		for( Entry<String,String> parameter : parameters.entrySet() ){
			formParams.add( new BasicNameValuePair( parameter.getKey() , parameter.getValue() ) );
		}
		return formParams;
	}

	private MultipartEntityBuilder packTextBody( Map<String,String> parameters , MultipartEntityBuilder builder ){
		if( parameters == null ) parameters = new HashMap<String,String>(0);
		for( Entry<String,String> parameter : parameters.entrySet() ){
			ContentType contentType = ContentType.create( DEFAULT_MIMETYPE, DEFAULT_CHARSET );
			builder.addTextBody( parameter.getKey() , parameter.getValue() , contentType );
		}
		return builder;
	}

	private MultipartEntityBuilder packContents( Object[] sNodeRefs , MultipartEntityBuilder builder ) throws IOException{
		if( sNodeRefs == null ) sNodeRefs = new String[0];
		for( Object sNodeRef : sNodeRefs ){
			NodeRef nodeRef = new NodeRef( sNodeRef.toString() );
			if( this.nodeService.exists( nodeRef ) && !this.alfContent.isContainer( nodeRef ) ){
				String fileName = this.nodeService.getProperty( nodeRef , ContentModel.PROP_NAME ).toString();
				ContentReader contentReader = this.contentService.getReader( nodeRef , ContentModel.PROP_CONTENT );
				InputStream inputStream = contentReader.getContentInputStream();
				ContentType contentType = ContentType.create( contentReader.getMimetype() );

				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				byte[] buffer = new byte[1024];
				int len;
				while ( ( len = inputStream.read( buffer ) ) > -1 ) {
					baos.write( buffer , 0 , len ) ;
				}
				baos.flush();
				byte[] byteArray = baos.toByteArray();
				baos.close();

				builder.addBinaryBody( ( sNodeRefs.length > 1 ) ? "file[]" : "file" , byteArray , contentType , fileName );

				inputStream.close();
			}
		}
		return builder;
	}

	@SuppressWarnings("unchecked")
	private <T> void addHeaders( T requestType , Object[] headers ){
		try{
			if( headers == null ) headers = new Object[0];
			for( Object headerSet : headers ){
				for( Entry<String,String> header : ( ( Map<String, String> ) headerSet ).entrySet() ){
					logger.info( "Adding header \"" + header.getKey() + ":" + header.getValue() + "\"." );
					( (HttpMessage) requestType ).addHeader( header.getKey() , header.getValue() );
				}
			}
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
	}

	public Response get( String url ) throws Exception {
		return get( url , null );
	}

	public Response get( String url , final Object[] headers ) throws Exception {
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpGet httpGet = new HttpGet( url );
		addHeaders( httpGet , headers );
		logger.debug( "Executing request \"" + httpGet.getRequestLine() + "\"." );
		Response response = httpClient.execute( httpGet , responseHandler );
		logger.debug( "Executed with status: \"" + response.getStatusLine().toString() + "\"." );
		httpClient.close();
		return response;
	}

	public Response delete( String url ) throws Exception {
		return delete( url , null );
	}

	public Response delete( String url , final Object[] headers ) throws Exception {
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpDelete httpDelete = new HttpDelete( url );
		addHeaders( httpDelete , headers );
		logger.debug( "Executing request \"" + httpDelete.getRequestLine() + "\"." );
		Response response = httpClient.execute( httpDelete , responseHandler );
		logger.debug( "Executed with status: \"" + response.getStatusLine().toString() + "\"." );
		httpClient.close();
		return response;
	}

	public Response post( String url , String requestMessage ) throws Exception {
		return post( url , requestMessage , null , null , null );
	}

	public Response post( String url , String requestMessage , final Object[] headers ) throws Exception {
		return post( url , requestMessage , null , null , headers );
	}

	public Response post( String url , String requestMessage , String mime , final Object[] headers ) throws Exception {
		return post( url , requestMessage , mime , null , headers );
	}

	public Response post( String url , String requestMessage , String mime , String encoding , final Object[] headers ) throws Exception {
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpPost httpPost = new HttpPost( url );
		addHeaders( httpPost , headers );
		Charset charset = ( encoding != null ) ? Charset.forName( encoding ) : DEFAULT_CHARSET;
		String mimetype = ( mime != null ) ? mime : DEFAULT_MIMETYPE;
		logger.debug( "Executing request \"" + httpPost.getRequestLine() + "\"." );

		StringEntity requestEntity = new StringEntity( requestMessage , ContentType.create( mimetype , charset ) );
		httpPost.setEntity( requestEntity );

		Response response = httpClient.execute( httpPost , responseHandler );
		logger.debug( "Executed with status: \"" + response.getStatusLine().toString() + "\"." );
		httpClient.close();
		return response;
	}

	// form
	public Response post( String url , Map<String,String> params ) throws Exception {
		return post( url , params , DEFAULT_ENCODING , null );
	}

	// form
	public Response post( String url , Map<String,String> params , final Object[] headers ) throws Exception {
		return post( url , params , DEFAULT_ENCODING , headers );
	}

	// form
	public Response post( String url , Map<String,String> params , String encoding , final Object[] headers ) throws Exception {
		List<NameValuePair> formParams = new ArrayList<NameValuePair>();
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpPost httpPost = new HttpPost( url );
		addHeaders( httpPost , headers );
		Charset charset = ( encoding != null ) ? Charset.forName( encoding ) : DEFAULT_CHARSET;
		logger.debug( "Executing request \"" + httpPost.getRequestLine() + "\"." );

		formParams = packParameters( params , formParams );

		UrlEncodedFormEntity requestEntity = new UrlEncodedFormEntity( formParams , charset );
		httpPost.setEntity( requestEntity );

		Response response = httpClient.execute( httpPost , responseHandler );
		logger.debug( "Executed with status: \"" + response.getStatusLine().toString() + "\"." );
		httpClient.close();
		return response;
	}

	// form with content/files
	public Response post( String url , Map<String,String> params , final Object[] contentNodeRefs , final Object[] headers ) throws Exception {
		MultipartEntityBuilder builder = MultipartEntityBuilder.create();
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpPost httpPost = new HttpPost( url );
		addHeaders( httpPost , headers );
		logger.debug( "Executing request \"" + httpPost.getRequestLine() + "\"." );

		builder = packContents( contentNodeRefs , builder );
		builder = packTextBody( params , builder );

		HttpEntity multipart = builder.build();
		httpPost.setEntity( multipart );

		Response response = httpClient.execute( httpPost , responseHandler );
		logger.debug( "Executed with status: \"" + response.getStatusLine().toString() + "\"." );
		httpClient.close();
		return response;
	}

	// simple
	public Response put( String url , String requestMessage ) throws Exception {
		return put( url , requestMessage , null , null , null );
	}

	// simple
	public Response put( String url , String requestMessage , final Object[] headers ) throws Exception {
		return put( url , requestMessage , null , null , headers );
	}

	// simple
	public Response put( String url , String requestMessage , String mime , final Object[] headers ) throws Exception {
		return put( url , requestMessage , mime , null , headers );
	}

	// simple / could be used for json requestMessage
	public Response put( String url , String requestMessage , String mime , String encoding , final Object[] headers ) throws Exception {
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpPut httpPut = new HttpPut( url );
		addHeaders( httpPut , headers );
		Charset charset = ( encoding != null ) ? Charset.forName( encoding ) : DEFAULT_CHARSET;
		String mimetype = ( mime != null ) ? mime : DEFAULT_MIMETYPE;
		logger.debug( "Executing request \"" + httpPut.getRequestLine() + "\"." );

		StringEntity requestEntity = new StringEntity( requestMessage , ContentType.create( mimetype , charset ) );
		httpPut.setEntity( requestEntity );

		Response response = httpClient.execute( httpPut , responseHandler );
		logger.debug( "Executed with status: \"" + response.getStatusLine().toString() + "\"." );
		httpClient.close();
		return response;
	}

	// form
	public Response put( String url , Map<String,String> params ) throws Exception {
		return put( url , params , DEFAULT_ENCODING , null );
	}

	// form
	public Response put( String url , Map<String,String> params , final Object[] headers ) throws Exception {
		return put( url , params , DEFAULT_ENCODING , headers );
	}

	// form
	public Response put( String url , Map<String,String> params , String encoding , final Object[] headers ) throws Exception {
		List<NameValuePair> formParams = new ArrayList<NameValuePair>();
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpPut httpPut = new HttpPut( url );
		addHeaders( httpPut , headers );
		Charset charset = ( encoding != null ) ? Charset.forName( encoding ) : DEFAULT_CHARSET;
		logger.debug( "Executing request \"" + httpPut.getRequestLine() + "\"." );

		formParams = packParameters( params , formParams );

		UrlEncodedFormEntity requestEntity = new UrlEncodedFormEntity( formParams , charset );
		httpPut.setEntity( requestEntity );

		Response response = httpClient.execute( httpPut , responseHandler );
		logger.debug( "Executed with status: \"" + response.getStatusLine().toString() + "\"." );
		httpClient.close();
		return response;
	}

	// form with content/files
	public Response put( String url , Map<String,String> params , final Object[] contentNodeRefs , final Object[] headers ) throws Exception {
		MultipartEntityBuilder builder = MultipartEntityBuilder.create();
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpPut httpPut = new HttpPut( url );
		addHeaders( httpPut , headers );
		logger.debug( "Executing request \"" + httpPut.getRequestLine() + "\"." );

		builder = packContents( contentNodeRefs , builder );
		builder = packTextBody( params , builder );

		HttpEntity multipart = builder.build();
		httpPut.setEntity( multipart );

		Response response = httpClient.execute( httpPut , responseHandler );
		logger.debug( "Executed with status: \"" + response.getStatusLine().toString() + "\"." );
		httpClient.close();
		return response;
	}
}
