package com.example.colabandroidhelper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

import com.amodtech.yaandroidffmpegwrapper.FfmpegJNIWrapper;

import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;


public class MainActivity extends ActionBarActivity {
	
	ServerSocket serverSocket;
	TextView stateTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        //Set the view
        setContentView(R.layout.activity_main);
        
        //Find the local IP address and display it
        try {
        	WifiManager wifiMan = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
        	WifiInfo wifiInf = wifiMan.getConnectionInfo();
        	int ipAddress = wifiInf.getIpAddress();
        	String hostaddr = String.format(Locale.US, "%d.%d.%d.%d", (ipAddress & 0xff),(ipAddress >> 8 & 0xff),(ipAddress >> 16 & 0xff),(ipAddress >> 24 & 0xff));
	        TextView ipTextView = (TextView)findViewById(R.id.ip_address);
	        ipTextView.setText(hostaddr);
	        Log.d("MainActivity onCreate","IP address: " + hostaddr);
		} catch (Exception e) {
			//log the error
			Log.d("MainActivity onCreate","error getting the IP address");
			e.printStackTrace();
		}
        
        //Display the state as starting server
        stateTextView = (TextView)findViewById(R.id.state);
        stateTextView.setText(R.string.state_starting_server);
        
        //Start an asynch task to wait for requests over the socket to compress videos
        //Based on modified version of approach outlined:
        //http://android-er.blogspot.hk/2014/08/bi-directional-communication-between.html
        Thread socketServerThread = new Thread(new SocketServerThread(this.getBaseContext()));
        socketServerThread.start(); 
        Log.d("MainActivity onCreate","Server started");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    protected void onDestroy() {
    	super.onDestroy();

     	//Close any open sockets
		if (serverSocket != null) {
			 try {
				 serverSocket.close();
			 } catch (IOException e) {
				 //Log error
				 Log.d("MainActivity onDestroy","error trying to close sockets");
			 }
		 }
	}
    
    private class SocketServerThread extends Thread {
    	//This Class represents a Socket Server. The server handles requests for video file compressions

    	static final int SocketServerPORT = 8080;
    	private Context ctx;
    	
    	//Contructor
    	public SocketServerThread(Context context) {
    		this.ctx = context;
    	}

    	@Override
    	public void run() {
    		Socket socket = null;
    		DataInputStream inputFileDIS = null;
    		FileOutputStream videoToCompressFOS= null;
    		BufferedOutputStream videoToCompressBOS = null;
    		FileInputStream compressedVideofileIS = null;
    		BufferedInputStream compressedVideofileBIS = null;
		    BufferedOutputStream socketBOS = null;
		    DataOutputStream socketDOS = null;
		    File videoFileToCompress = null;
		    File compressedVideoFile = null;

		    while (true) {
	    		try {
	    			serverSocket = new ServerSocket(SocketServerPORT);
	    			MainActivity.this.runOnUiThread(new Runnable() {
	    				@Override
		    			public void run() {
		    				stateTextView.setText(R.string.state_server_started);
		    			}
	    			});
	
	    			while (true) {
	    				//Accept connections from clients sending videos to compress
	    				Log.d("MainActivity SocketServerThread Run","Waiting for connection");
	        			MainActivity.this.runOnUiThread(new Runnable() {
	        	    	    @Override
	    	    			public void run() {
	        	    	    	stateTextView.setText(R.string.state_waiting_for_connection);
	    	    			}
	        			});
	    				socket = serverSocket.accept();
	 
	    				//Handle the message - read the data in and store the file locally first
	        			MainActivity.this.runOnUiThread(new Runnable() {
	        	    	    @Override
	    	    			public void run() {
	        	    	    	stateTextView.setText(R.string.state_connection_received);
	    	    			}
	        			});
	    				inputFileDIS = new DataInputStream(socket.getInputStream());
						int bufferSize = socket.getReceiveBufferSize();
						Log.d("MainActivity SocketServerThread Run","Receive buffer size: " + bufferSize);
						videoToCompressFOS = null;
					    videoToCompressBOS = null;
					    
						try {
							//Create the file and the steams to enable us write to it. First check if a temp video file already exists 
							//and if so delete it - we don't want more than one at any given time.
							videoFileToCompress = new File(Environment.getExternalStorageDirectory(), "TempVideoToCompress.mp4");
							if(videoFileToCompress.exists()) {
								//Delete the file and create a new one
								boolean fileDeleted = videoFileToCompress.delete();
								if (!fileDeleted) {
									//log error and return
									Log.d("MainActivity SocketServerThread Run","videoFileToCompress: old file not deleted");
									return;
								}
							}
							boolean fileCreated = videoFileToCompress.createNewFile();
							if (!fileCreated) {
								//log error and return
								Log.d("MainActivity SocketServerThread Run","videoFileToCompress: file not created");
								return;
							}
							videoToCompressFOS = new FileOutputStream(videoFileToCompress);
							videoToCompressBOS = new BufferedOutputStream(videoToCompressFOS);
						} catch (FileNotFoundException e) {
							Log.d("MainActivity SocketServerThread Run","Fileoutputstream: file not found exception");
							e.printStackTrace();
						}
						
		    			MainActivity.this.runOnUiThread(new Runnable() {
		    				@Override
	    	    			public void run() {
	    	    	    		stateTextView.setText(R.string.state_receiving_file);
	    	    			}
	        			});
					    		
					    //The first part of the message should be the length of the file being transfered - read it first and
					    //then write from the second byte onwards to the buffer
					    byte[] bytes = new byte[bufferSize];
					    boolean reportCount = true;
					    long fileSize = inputFileDIS.readLong();
					    Log.d("MainActivity onCreate","Video Chunk incoming fileSize: " + fileSize);
					    
					    //Now read in the rest of the file up to the final byte indicated by the size
					    long totalCount = 0;
					    int thisReadCount = 0;
					    while (totalCount < fileSize && (thisReadCount = inputFileDIS.read(bytes)) != -1) {
					    	totalCount += thisReadCount;
					    	if (reportCount) {
					    		Log.d("MainActivity SocketServerThread Run","Count this read is: " + thisReadCount);
					    	}
					    	videoToCompressBOS.write(bytes, 0, thisReadCount);
					    }
					    //Write the final buffer read in - this is necessary as thisReadCount will be set to -1 
					    //when the end of stream id detected even when it has read in some bytes while detecting the end
					    //of stream
					    videoToCompressBOS.write(bytes);
					    Log.d("MainActivity SocketServerThread Run","video file received");
					    Log.d("MainActivity SocketServerThread Run","totalCount: " + totalCount);
					    Log.d("MainActivity SocketServerThread Run","thisReadCount: " + thisReadCount);
	
					    videoToCompressBOS.flush();
					    videoToCompressBOS.close();
					    //inputFileDIS.close();			    
	
			    	    //Now compress the file
		    			MainActivity.this.runOnUiThread(new Runnable() {
		    	    	    @Override
	    	    			public void run() {
	    	    	    		stateTextView.setText(R.string.state_compressing_video);
	    	    			}
		        		});
	
					    compressedVideoFile = new File(Environment.getExternalStorageDirectory(), "TempCompressedVideo.mp4");
						if(compressedVideoFile.exists()) {
							//Delete the file if it already exists (from a previous run)
							boolean fileDeleted = compressedVideoFile.delete();
							if (!fileDeleted) {
								//log error and return
								Log.d("MainActivity SocketServerThread Run","compressedVideoFile: old file not deleted");
								return;
							}
						}
				    	String argv[] = {"ffmpeg", "-i", videoFileToCompress.getAbsolutePath(), "-strict", "experimental", 
				    							"-acodec", "aac", compressedVideoFile.getAbsolutePath()};
				    	Log.d("MainActivity SocketServerThread Run","Calling ffmpegWrapper");
				    	int ffmpegWrapperreturnCode = FfmpegJNIWrapper.call_ffmpegWrapper(this.ctx, argv);
				    	Log.d("MainActivity SocketServerThread Run","ffmpegWrapperreturnCode: " + ffmpegWrapperreturnCode);
	                    
	                    //Send the compressed file back over the socket
		    			MainActivity.this.runOnUiThread(new Runnable() {
		    				@Override
	    	    			public void run() {
	    	    	    		stateTextView.setText(R.string.state_sending_compressed_video);
	    	    			}
	        			});
		    			//First send the file size
		    			Log.d("MainActivity SocketServerThread Run","Sending compessed file size back");
					    compressedVideofileIS = new FileInputStream(compressedVideoFile);
					    compressedVideofileBIS = new BufferedInputStream(compressedVideofileIS);
					    socketBOS = new BufferedOutputStream(socket.getOutputStream());
					    socketDOS = new DataOutputStream(socketBOS);
					    socketDOS.writeLong(compressedVideoFile.length());
					    //Now send all the bytes in the file
					    Log.d("MainActivity SocketServerThread Run","Sending compessed video file back");
					    thisReadCount = 0;
					    while ((thisReadCount = compressedVideofileBIS.read(bytes)) > 0) {
					    	socketDOS.write(bytes, 0, thisReadCount);
					    }
	
					    //Tidy up streams
					    Log.d("MainActivity SocketServerThread Run","Tidying up");
					    socketDOS.flush();
					    socketDOS.close();
					    socketBOS.close();
					    compressedVideofileIS.close();
					    compressedVideofileBIS.close();
					    
		    			MainActivity.this.runOnUiThread(new Runnable() {
		    				@Override
			    			public void run() {
			    				stateTextView.setText(R.string.state_sent_compressed_file);
			    			}
		    			});
			    	    
			    	    //Delete temporary files and close socket
					    if (compressedVideoFile.delete() != true) {
					    	Log.d("MainActivity SocketServerThread Run","error closing compressedVideoFile");
					    }
					    if (videoFileToCompress.delete() != true) {
					    	Log.d("MainActivity SocketServerThread Run","error closing videoFileToCompress");
					    }
					    socket.close();
	    			}
	    	   } catch (IOException e) {
		    	   //Log error
	    		   Log.d("MainActivity SocketServerThread Run","error in socket server");
		    	   e.printStackTrace();
	    	   } finally {
	    		   //Tidy up...
	    		   try {
	    			   if (compressedVideoFile != null) {
	    				   compressedVideoFile.delete();
	    			   }
	    			   
	    			   if (videoFileToCompress != null) {
	    				   videoFileToCompress.delete();
	    			   }
	    			   
		    		   if (socket != null) {
		    			   socket.close();
		    		   }
		
		    		   if (inputFileDIS != null) {
		    			   inputFileDIS.close();
		    		   }
		
		    		   if (videoToCompressFOS != null) {
		    			   videoToCompressFOS.close();
		    		   }
		    		   
		    		   if (videoToCompressBOS != null) {
		  				   videoToCompressBOS.close();
		    		   }
		    	
		    		   if (compressedVideofileIS != null) {
		    			   compressedVideofileIS.close();
		    		   } 
		    		   
		    		   if (socketBOS != null) {
		    			   socketBOS.close();
		    		   } 
		    		   
		    		   if (compressedVideofileBIS != null) {
		    			   compressedVideofileBIS.close();
		    		   } 
				   } catch (IOException e) {
					   Log.d("MainActivity SocketServerThread Run","error tidying up");
					   e.printStackTrace();
				   }
	    	   }
	    	}  
	    }
    }
}
