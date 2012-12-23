/*
 * Copyright 2006 Antonio S. R. Gomes Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless
 * required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package net.sf.jreloader.agent;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Class file transformer (this class is tricky/狡猾的).
 * @author Antonio S. R. Gomes
 */
public class Transformer implements ClassFileTransformer {

    private Logger log = new Logger("Transformer");

    private Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private ReloadThread thread;

    public Transformer() {

    	//获得所有配置的.class文件的路径
        String[] dirNames = System.getProperty("jreloader.dirs", ".").split("\\,");

        for (String dirName : dirNames) {
            File d = new File(dirName).getAbsoluteFile();
            log.info("Added class dir '" + d.getAbsolutePath() + "'");
            scan(d, d);
        }
        findGroups();
        log.info(" \\-- Found " + entries.size() + " classes");
        thread = new ReloadThread();
        thread.start();
    }

    private FileFilter filter = new FileFilter() {
    	
    	//我只想要目录和.class文件
        public boolean accept(File pathname) {
            return pathname.isDirectory() || pathname.getName().endsWith(".class");
        };
    };

    private void scan(File base, File dir) {
        File[] files = dir.listFiles(filter);
        if (files != null) {
            for (File file : files) {
            	
            	//若是文件
                if (file.isFile()) {
                    Entry e = new Entry();
                    
                    //获得AAA.class的AAA名字
                    e.name = nameOf(base, file);
                    log.debug(" found class " + e.name);
                    e.file = file;
                    e.lastModified = file.lastModified();
                    
                    //缓存.class文件实体
                    entries.put(e.name, e);
                } else {
                	
                	//递归
                    scan(base, file);
                }
            }
        }
    }

    private void findGroups() {
        for (java.util.Map.Entry<String, Entry> e : entries.entrySet()) {
            String n = e.getValue().name;
            
            //处理内部类归入组的问题
            int p = n.indexOf('$');
            if (p != -1) {
                String parentName = n.substring(0, p);
                entries.get(parentName).addChild(e.getValue());
            }
        }
    }

    private String nameOf(File base, File f) {
        String s = base.getAbsolutePath();
        String s1 = f.getAbsolutePath();
        return s1
            .substring(s.length() + 1, s1.length() - ".class".length())
                .replace(File.separatorChar, '/');
    }

    private class Entry {

        String name;
        File file;
        long lastModified;
        List<Entry> children;
        Entry parent;
        WeakReference<ClassLoader> loaderRef;

        void addChild(Entry e) {
        	
        	//这种神奇的初始化过程
            children = (children == null) ? new ArrayList<Entry>() : children;
            children.add(e);
            e.parent = this;
        }

        //是否已经修改
        boolean isDirty() {
            return file.lastModified() > lastModified;
        }

        void clearDirty() {
            // System.err.println("clearDirty: " + name);
        	
        	//统一所有.class文件实体的最后修改时间
            lastModified = file.lastModified();
            if (children != null) {
                for (Entry e : children) {
                    e.lastModified = e.file.lastModified();
                }
            }
        }

        /**
         * 递归强制修改lastModified时间,使得实体的lastModified不正确为脏数据
         */
        public void forceDirty() {
            if (parent == null) {
                lastModified = 0;
                if (children != null) {
                    for (Entry e : children) {
                        e.lastModified = 0;
                    }
                }
            } else {
                parent.forceDirty();
            }
        }

    }

    /**
     * 接口方法重写
     */
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] currentBytes) throws IllegalClassFormatException {
        String clname = "bootstrap";
        if (loader != null) {
            clname = loader.getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(loader));
        }
        Entry e = entries.get(className);
        if (e != null) {
            log.debug(clname + " is loading " + className);
        }
        
        //为每个.class文件关联类加载器
        if (e != null && loader != null) {
            e.loaderRef = new WeakReference<ClassLoader>(loader);
        }
        return null;
    }

    class ReloadThread extends Thread {
    	
        public ReloadThread() {
        	
        	//后台线程,最高权限
            super("ReloadThread");
            setDaemon(true);
            setPriority(MAX_PRIORITY);
        }

        @Override
        public void run() {
            try {
                sleep(5000);
            } catch (InterruptedException e) {
            }
            while (true) {
                try {
                    sleep(3000);
                } catch (InterruptedException e) {
                }
                
                //这个暂停地方放得好
                if (System.getProperty("jreloader.pauseReload") != null) {
                    continue;
                }
                log.debug("Checking changes...");
                List<Entry> aux = new ArrayList<Entry>(entries.values());
                
                //这是为何??
                for (Entry e : aux) {
                    if (e.isDirty()) {
                        e.forceDirty();
                    }
                }
                for (Entry e : aux) {
                	
                	//若.class文件被修改且这个.class文件不存在内部类
                    if (e.isDirty() && e.parent == null) {
                        log.debug("Reloading " + e.name);
                        try {
                            reload(e);
                        } catch (Throwable t) {
                            log.error("Could not reload " + e.name, t);
                            System.err
                                .println("[JReloader:ERROR] Could not reload class "
                                        + e.name.replace('/', '.'));
                        }
                        
                        //加载完后修正最后修改时间
                        e.clearDirty();
                    }
                }
            }
        }

        private List<ClassDefinition> cdefs = new LinkedList<ClassDefinition>();

        /**
         * 重新加载类
         * @param e
         * @throws IOException
         * @throws ClassNotFoundException
         * @throws UnmodifiableClassException
         * @author liangqiye / 2012-12-23 下午4:43:20
         */
        private void reload(Entry e)
            throws IOException, ClassNotFoundException, UnmodifiableClassException {
            System.err.println(e.file);
            cdefs.clear();
            if (e.loaderRef != null) {
            	
            	//获得每个类的类加载器
                ClassLoader cl = e.loaderRef.get();
                if (cl != null) {
                    request(e, cl);
                    if (e.children != null) {
                        for (Entry ce : e.children) {
                            request(ce, cl);
                        }
                    }
                    // System.err.println(cdefs);
                    
                    //list -> array
                    //Instrumentation.redefineClasses
                    //cdefs.toArray(new ClassDefinition[0] 值得学习
                    Agent.inst.redefineClasses(cdefs.toArray(new ClassDefinition[0]));
                } else {
                    e.loaderRef = null;
                }
            }
        }

        private void request(Entry e, ClassLoader cl)
            throws IOException, ClassNotFoundException {
        	
        	//处理类文件的数据和名字
            byte[] bytes = loadBytes(e.file);
            String className = e.name.replace('/', '.');
            
            //重新加载类
            Class<?> clazz = cl.loadClass(className);
            log.info("Requesting reload of " + e.name);
            System.out.println("[JReloader:INFO ] Reloading class " + className);
            
            //保存新类和类文件的字节数组
            cdefs.add(new ClassDefinition(clazz, bytes));
        }

    }

    /**
     * 读取类文件
     * @param classFile
     * @return
     * @throws IOException
     * @author liangqiye / 2012-12-23 下午4:42:33
     */
    public static byte[] loadBytes(File classFile) throws IOException {
        byte[] buffer = new byte[(int) classFile.length()];
        FileInputStream fis = new FileInputStream(classFile);
        BufferedInputStream bis = new BufferedInputStream(fis);
        bis.read(buffer);
        bis.close();
        return buffer;

    }

}
