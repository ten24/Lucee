package lucee.runtime.osgi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;

import lucee.commons.io.SystemUtil;

import lucee.loader.engine.CFMLEngineFactory;
import lucee.runtime.config.Config;
import lucee.runtime.config.ConfigImpl;
import lucee.runtime.engine.ThreadLocalPageContext;
import lucee.runtime.text.xml.XMLUtil;

public class EnvClassLoader extends URLClassLoader {

	private static float FROM_SYSTEM = 1;
	private static float FROM_BOOTDELEGATION = 2;
	private static float FROM_CALLER = 3;

	private static SoftReference<String> EMPTY = new SoftReference<String>(null);
	private static Map<SoftReference<String>, SoftReference<String>> notFound = new java.util.concurrent.ConcurrentHashMap<>();

	private ConfigImpl config;
	// private Map<String, SoftReference<Coll>> callerCache=new ConcurrentHashMap<String,
	// SoftReference<Coll>>();

	private static final short CLASS=1;
	private static final short URL=2;
	private static final short STREAM=3;

	private static ThreadLocal<Boolean> inside = new ThreadLocal<Boolean>() {
		@Override
		protected Boolean initialValue() {
			return Boolean.FALSE;
		}
	};
	
	public EnvClassLoader(ConfigImpl config) {
		super(new URL[0],config!=null?config.getClassLoaderCore():new lucee.commons.lang.ClassLoaderHelper().getClass().getClassLoader());
		this.config=config;
		
	}



	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException   {
		return loadClass(name, false);
	}
	
	@Override
	public URL getResource(String name) {
		return (java.net.URL) load(name, URL,true);
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		return (InputStream) load(name, STREAM, true);
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		List<URL> list=new ArrayList<URL>();
		URL url = (URL)load(name,URL,false);
		if(url!=null) list.add(url);
		return new E<URL>(list.iterator());
	}

	@Override
	protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		// First, check if the class has already been loaded
		Class<?> c = findLoadedClass(name);
		if(c==null)c = (Class<?>) load(name, CLASS,true);
		if(c==null)c = findClass(name);
		if (resolve)resolveClass(c);
		return c;
	}

	private synchronized Object load(String name, short type, boolean doLog) {
		Object obj=null;
		// cache.get(name);

		// if (name.equals("org.hibernate.hql.ast.HqlToken")) print.ds();

		// PATCH XML
		if ((name + "").startsWith("META-INF/services") && !inside.get()) {
			inside.set(Boolean.TRUE);
			try {
				if (name.equalsIgnoreCase("META-INF/services/javax.xml.parsers.DocumentBuilderFactory")) {
					if (type == URL) return XMLUtil.getDocumentBuilderFactoryResource();
					else if (type == STREAM) return new ByteArrayInputStream(XMLUtil.getDocumentBuilderFactoryName().getBytes());
				}
				else if (name.equalsIgnoreCase("META-INF/services/javax.xml.parsers.SAXParserFactory")) {
					if (type == URL) return XMLUtil.getSAXParserFactoryResource();
					else if (type == STREAM) return new ByteArrayInputStream(XMLUtil.getSAXParserFactoryName().getBytes());
				}
				else if (name.equalsIgnoreCase("META-INF/services/javax.xml.transform.TransformerFactory")) {
					if (type == URL) return XMLUtil.getTransformerFactoryResource();
					else if (type == STREAM) return new ByteArrayInputStream(XMLUtil.getTransformerFactoryName().getBytes());
				}
				else if (name.equalsIgnoreCase("META-INF/services/org.apache.xerces.xni.parser.XMLParserConfiguration")) {
					if (type == STREAM) return new ByteArrayInputStream(XMLUtil.getXMLParserConfigurationName().getBytes());
				}
			}
			catch (IOException e) {}
			finally {
				inside.set(Boolean.FALSE);
			}
		}

		// PATCH for com.sun
		if ((name + "").startsWith("com.sun.")) {
			ClassLoader loader = CFMLEngineFactory.class.getClassLoader();
			obj = _load(loader, name, type);
			if (obj != null) {
				return obj;
			}
		}
		
		// callers classloader context
		for (ClassLoader cl: SystemUtil.getClassLoaderContext(true)) {
			obj = _load(cl, name, type);
			if(obj!=null) {
				return obj;
			}
		}
		return obj;
   }


	private Object _load(ClassLoader cl, String name, short type) {
		Object obj=null;
		Bundle b = null;
		if(cl!=null) {
			try {
				if (type == CLASS) {
					if (cl instanceof BundleReference) {
						b = ((BundleReference) cl).getBundle();
						if (notFound.containsKey(
								new SoftReference<String>(new StringBuilder(b.getSymbolicName()).append(':').append(b.getVersion()).append(':').append(name).toString())))
							return null;
						else obj = cl.loadClass(name);
					}
					else obj = cl.loadClass(name);
				}
				else if(type==URL)obj = cl.getResource(name);
				else obj = cl.getResourceAsStream(name);
			} 
			catch (ClassNotFoundException cnfe) {
				if (b != null)
					notFound.put(new SoftReference<String>(new StringBuilder(b.getSymbolicName()).append(':').append(b.getVersion()).append(':').append(name).toString()), EMPTY);
			}
			catch (Exception e) {}
			
		}
		return obj;
	}

	private String toType(short type) {
		if(CLASS==type) return "class";
		if(STREAM==type) return "stream";
		return "url";
	}

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException {
		throw new ClassNotFoundException("class "+name+" not found in the core, the loader and all the extension bundles");
	}
	
	private static class E<T> implements Enumeration<T> {
		
		private Iterator<T> it;

		private E(Iterator<T> it){
			this.it=it;
		}

		@Override
		public boolean hasMoreElements() {
			return it.hasNext();
		}

		@Override
		public T nextElement() {
			return it.next();
		}
		
	}
	
	//////////////////////////////////////////////////
	// URLClassloader methods, need to be supressed //
	//////////////////////////////////////////////////
	@Override
	public URL findResource(String name) {
		return getResource(name);
	}

	@Override
	public Enumeration<URL> findResources(String name) throws IOException {
		return getResources(name);
	}
	
}