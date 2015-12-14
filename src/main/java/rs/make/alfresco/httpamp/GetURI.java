package rs.make.alfresco.httpamp;

import java.io.IOException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import org.alfresco.repo.jscript.BaseScopableProcessorExtension;

public class GetURI extends BaseScopableProcessorExtension {
    public String responseEntity = null;
    public HttpResponse fetchResponse( String url ) throws Exception {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet httpget = new HttpGet( url );
        System.out.println("Executing request " + httpget.getRequestLine());
        ResponseHandler<HttpResponse> responseHandler = new ResponseHandler<HttpResponse>() {
            @Override
            public HttpResponse handleResponse( final HttpResponse responseObj ) throws ClientProtocolException, IOException {
                HttpEntity entity = responseObj.getEntity();
                responseEntity = ( entity != null ) ? EntityUtils.toString( entity ) : null;
                return responseObj;
            }
        };
        HttpResponse responseBody = httpclient.execute( httpget , responseHandler );
        httpclient.close();
        return responseBody;
    }
}
