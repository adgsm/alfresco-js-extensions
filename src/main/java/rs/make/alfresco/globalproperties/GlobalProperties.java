package rs.make.alfresco.globalproperties;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.log4j.Logger;

public class GlobalProperties {
	private static Logger logger = Logger.getLogger( GlobalProperties.class );
	private static final String PROPERTIES_FILE = "alfresco-global.properties";
	protected Properties properties = new Properties();

	protected Properties load() throws Exception {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		InputStream inputStream = classLoader.getResourceAsStream( PROPERTIES_FILE );
		try {
			properties.load( inputStream );
			//String alfData = properties.getProperty( "dir.root" );
		}
		catch ( IOException e ) {
			logger.error( "Error while loading property file: " + e.getMessage() );
			e.printStackTrace();
		}
		finally {
			if ( inputStream != null ) {
				try {
					inputStream.close();
				}
				catch (IOException e) {
					logger.error( "Error while closing property file: " + e.getMessage() );
					e.printStackTrace();
				}
			}
		}
		return properties;
	}

	public String getProperty( String property ) throws Exception {
		if( properties.isEmpty() ) load();
		return properties.getProperty( property );
	}
}
