/*
 * Copyright (C) 2012 GZ-ISCAS Inc., All Rights Reserved.
 */
package com.mtea.macrotea_jreloader_study;

/**
 * @author 	liangqiye@gz.iscas.ac.cn
 * @version 1.0 , 2012-12-23 下午3:40:27
 */
public class AutoThread extends Thread{

	/* (non-Javadoc)
	 * @see java.lang.Thread#run()
	 */
	@Override
	public void run() {
		while (true) {
			new SysPrinter().doPrint();
			try {
				sleep(3000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	

}
