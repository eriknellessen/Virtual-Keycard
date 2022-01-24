package de.nellessen.virtual_keycard;

import de.nellessen.muscle_card_on_android.MSCHostApduService;
import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

public class LastLoginTryWithGUIActivity extends Activity {
	MSCHostApduService MyHostApduService = MSCHostApduService.getInstance();
	int loginTriesLeft;
	int unblockTriesLeft;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_last_login_try_with_gui);

		/*if (savedInstanceState == null) {
			getFragmentManager().beginTransaction()
					.add(R.id.container, new PlaceholderFragment()).commit();
		}*/
		// Get the message from the intent
	    Intent intent = getIntent();
	    loginTriesLeft = intent.getIntExtra(MainActivity.LOGIN_TRIES_LEFT, 0);
	    unblockTriesLeft = intent.getIntExtra(MainActivity.UNBLOCK_TRIES_LEFT, 0);
		refresh();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.last_login_try_with_gui, menu);
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

	/**
	 * A placeholder fragment containing a simple view.
	 */
	public static class PlaceholderFragment extends Fragment {

		public PlaceholderFragment() {
		}

		@Override
		public View onCreateView(LayoutInflater inflater, ViewGroup container,
				Bundle savedInstanceState) {
			/*View rootView = inflater
					.inflate(R.layout.fragment_last_login_try_with_gui,
							container, false);
			return rootView;*/
			return null;
		}
	}
	
	public void loginOrUnblock(View view){
		EditText pinOrPuk = (EditText) findViewById(R.id.get_pin_puk);
    	String pinOrPukString = pinOrPuk.getText().toString();
    	if(loginTriesLeft > 0){
    		if(!MyHostApduService.verifyPin((byte) 1, pinOrPukString)){
    			loginTriesLeft--;
    			refresh();
    		}
    		else
    			finish();
    	} else if(unblockTriesLeft > 0) {
    		if(!MyHostApduService.unblockPin((byte) 1, pinOrPukString)){
    			unblockTriesLeft--;
    			refresh();
    		}
    		else
    			finish();
    	}
	}
	
	private void refresh(){
		TextView message = (TextView) findViewById(R.id.last_login_try_header);
    	String messageString = "Login tries left: " + loginTriesLeft + ".\n"
    			+ "Unblock tries left: " + unblockTriesLeft + ".\n";
    	if(loginTriesLeft > 0){
    		messageString = messageString.concat("Please try to login.");
    	} else if(unblockTriesLeft > 0){
    		messageString = messageString.concat("Please try to unblock the PIN.");
    	} else {
    		messageString = messageString.concat("You locked yourself out."
    				+ "Reinstall virtual keycard."
    				+ "Please note that your keys are lost.");
    	}
    	char[] messageChars = messageString.toCharArray();
    	message.setText(messageChars, 0, messageChars.length);
		
	}

}
