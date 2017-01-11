package rs.make.alfresco.callback;

import org.alfresco.repo.jscript.BaseScopableProcessorExtension;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.apache.log4j.Logger;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.WrapFactory;

public class Callback extends BaseScopableProcessorExtension {
	private static Logger logger = Logger.getLogger( Callback.class );

	private static final WrapFactory wrapFactory = new WrapFactory();

	private String authenticated() throws Exception{
		String user = null;
		try {
			user = AuthenticationUtil.getFullyAuthenticatedUser();
			if( user == null || user.equals( "" ) ) {
				throw new Exception( "Unauthenticated" );
			}
		}
		catch( Exception e ) {
			logger.error( e );
		}
		return user;
	}

	public void run( final Function funct , final Object[] args ) {
		RunAsWork<Object> raw = new RunAsWork<Object>() {
			public Object doWork() throws Exception {
				Context context = Context.enter();
				context.setWrapFactory( wrapFactory );
				Scriptable scope = SharedScope.get();
				if( scope == null ){
					ScriptableObject scopeObject = context.initStandardObjects( null , true );
					scope = context.newObject( scopeObject );
				}
				funct.call( context , scope , scope , args );
				SharedScope.unset();
				Context.exit();
				return null;
			}
		};
		try{
			if( funct == null ) {
				throw new Exception( "Function is not passed." );
			}
			String authenticatedUser = AuthenticatedUser.get();
			if( authenticatedUser == null ) authenticatedUser = authenticated();
			if( authenticatedUser == null ) {
				throw new Exception( "User authentication data is not passed." );
			}
			// run callback as script authenticated user
			AuthenticationUtil.runAs( raw , authenticatedUser );
			AuthenticatedUser.unset();
		}
		catch( Exception e ){
			logger.error( e );
			e.printStackTrace();
		}
	}

	public static class SharedScope {
		public static final ThreadLocal<Scriptable> sharedScope = new ThreadLocal<Scriptable>();
		public static void set( Scriptable scope ) {
			sharedScope.set( scope );
		}
		public static void unset() {
			sharedScope.remove();
		}
		public static Scriptable get() {
			return sharedScope.get();
		}
	}

	public static class AuthenticatedUser {
		public static final ThreadLocal<String> authenticatedUser = new ThreadLocal<String>();
		public static void set( String userName ) {
			authenticatedUser.set( userName );
		}
		public static void unset() {
			authenticatedUser.remove();
		}
		public static String get() {
			return authenticatedUser.get();
		}
	}
}
