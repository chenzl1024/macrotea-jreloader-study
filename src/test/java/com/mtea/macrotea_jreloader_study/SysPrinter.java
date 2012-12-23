/*
 * Copyright (C) 2012 GZ-ISCAS Inc., All Rights Reserved.
 */
package com.mtea.macrotea_jreloader_study;

/**
 * @author 	liangqiye@gz.iscas.ac.cn
 * @version 1.0 , 2012-12-23 下午3:39:29
 */
public class SysPrinter {
	
	public void doPrint(){
		//在线程运行期间,您可以在这里随时添加内容,测试是否能够热部署
		System.out.println("test print - 1");
		System.out.println("test print - 2");
		//System.out.println("test print - 3");
		//System.out.println("test print - 4");
		
		//当添加已有方法则起效,如showMe()
		showMe();
		
		//若新增方法,且在doPrint()方法体重运行则有问题
		//newMethod();
	}
	
	public void showMe(){
		System.out.println("macrotea@qq.com");
	}

}
