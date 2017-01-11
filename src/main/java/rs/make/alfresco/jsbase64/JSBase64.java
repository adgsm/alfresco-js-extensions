package rs.make.alfresco.jsbase64;

import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.jscript.BaseScopableProcessorExtension;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

import java.io.InputStream;

import org.apache.log4j.Logger;

/**
* @desc expose Base64 binary manipulation to JavaScript
*
* @filename Base64.java
* @author Momcilo Dzunic (momcilo@dzunic.net)
* @licence MIT
*/
public class JSBase64 extends BaseScopableProcessorExtension {
	protected ContentService contentService;
	public ContentService getContentService() {
		return contentService;
	}
	public void setContentService( ContentService contentService ) {
		this.contentService = contentService;
	}

	private static Logger logger = Logger.getLogger( JSBase64.class );
	public String encodedFileString = null;

	/**
	* Expose file content byte array as base64 string
	*
	* @param sNodeRef - string
	* @return String a {@link java.lang.String}
	*/
	public String encodeContent( String sNodeRef ) throws Exception {
		try {
			NodeRef nodeRef = new NodeRef( sNodeRef );
			InputStream inputStream = this.contentService.getReader( nodeRef , ContentModel.PROP_CONTENT ).getContentInputStream();
			byte[] arrBuff = IOUtils.toByteArray( inputStream );
			encodedFileString = encodeFile( arrBuff );
			inputStream.close();
		}
		catch( Exception e ){
			logger.error( e );
		}
		return encodedFileString;
	}

	/**
	* Encodes the byte array into base64 string
	*
	* @param fileByteArray - byte array
	* @return String a {@link java.lang.String}
	*/
	public static String encodeFile( byte[] fileByteArray ) {
		return Base64.encodeBase64URLSafeString( fileByteArray );
	}

	/**
	* Decodes the base64 string into byte array
	*
	* @param fileDataString - a {@link java.lang.String}
	* @return byte array
	*/
	public static byte[] decodeFile( String fileDataString ) {
		return Base64.decodeBase64( fileDataString );
	}
}
