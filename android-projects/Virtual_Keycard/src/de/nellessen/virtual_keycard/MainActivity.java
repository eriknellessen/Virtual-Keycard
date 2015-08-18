package de.nellessen.virtual_keycard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import de.nellessen.muscle_card_on_android.AskForOk;
import de.nellessen.muscle_card_on_android.LastLoginTryWithGUI;
import de.nellessen.muscle_card_on_android.MSCHostApduService;

public class MainActivity extends Activity implements AskForOk, LastLoginTryWithGUI{
	
	private MSCHostApduService MyHostApduService;
	
	public final static String PIN_NO = "de.nellessen.virtual_keycard.PIN_NO";
	public final static int CreatePinPersistentRequestCode = 1;
	
	public final static String LOGIN_TRIES_LEFT = "de.nellessen.virtual_keycard.LOGIN_TRIES_LEFT";
	public final static String UNBLOCK_TRIES_LEFT = "de.nellessen.virtual_keycard.UNBLOCK_TRIES_LEFT";
	
	boolean askForOkResult;
	
	boolean lastLoginTryWithGUI;
	public final static int LastLoginTryWithGUIRequestCode = 2;
	
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MyHostApduService = MSCHostApduService.getInstance(this);
        if(!MyHostApduService.existsPin((byte) 1)){
            	Intent intent = new Intent(this, CreatePinPersistent.class);
            	intent.putExtra(PIN_NO, (byte) 1);
            	startActivityForResult(intent, CreatePinPersistentRequestCode);
        } else {
        MyHostApduService.setup_mscapplet();
        refresh(null);
        }
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == CreatePinPersistentRequestCode && resultCode == Activity.RESULT_OK){
        	MyHostApduService.setup_mscapplet();
        	refresh(null);
        }
        if(requestCode == LastLoginTryWithGUIRequestCode){
        	lastLoginTryWithGUI = false;
        }
    }
    
    //Refreshes the status view. Called when user pushed the refresh-button.
    public void refresh(View view){
    	char[] textToShow = MyHostApduService.getKeyCardStatus().toCharArray();
	    
		TextView showStatusInfoField = (TextView) findViewById(R.id.show_status_info_field);
		showStatusInfoField.setMovementMethod(new ScrollingMovementMethod());
		showStatusInfoField.setText(textToShow, 0, textToShow.length);
    }
    
    //Idea taken from http://stackoverflow.com/questions/17899328/this-handler-class-should-be-static-or-leaks-might-occur-com-test-test3-ui-main
  	private static class HandlerClass extends Handler {

  		public HandlerClass() {
  		}

  		@Override
  		public void handleMessage(Message msg) {
  			throw new RuntimeException();
  		}

  	};
    
    @Override
    public boolean askForOk(String data){
    	//Code taken from http://stackoverflow.com/questions/2028697/dialogs-alertdialogs-how-to-block-execution-while-dialog-is-up-net-style
    	// make a handler that throws a runtime exception when a message is received
    	final HandlerClass handler = new HandlerClass();

        // make a text input dialog and show it
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        
        alert.setTitle("Permission request");
        alert.setMessage(data);
        alert.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                askForOkResult = true;
                handler.sendMessage(handler.obtainMessage());
            }
        });
        alert.setNegativeButton("No", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
            	askForOkResult = false;
                handler.sendMessage(handler.obtainMessage());
            }
        });
        alert.show();

        // loop till a runtime exception is triggered.
        try { Looper.loop(); }
        catch(RuntimeException e2) {}

        return askForOkResult;
    }
    
    @Override
    public void lastLoginTryWithGUI(int loginTriesLeft, int unblockTriesLeft){
    	Intent intent = new Intent(this, LastLoginTryWithGUIActivity.class);
    	intent.putExtra(LOGIN_TRIES_LEFT, loginTriesLeft);
    	intent.putExtra(UNBLOCK_TRIES_LEFT, unblockTriesLeft);
    	if(!lastLoginTryWithGUI){
    		startActivityForResult(intent, LastLoginTryWithGUIRequestCode);
    		lastLoginTryWithGUI = true;
    	}
    }
}
