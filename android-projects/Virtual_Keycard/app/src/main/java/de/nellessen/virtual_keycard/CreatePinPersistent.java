package de.nellessen.virtual_keycard;

import java.security.SecureRandom;

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
import de.nellessen.muscle_card_on_android.MSCHostApduService;

public class CreatePinPersistent extends Activity {

	byte pinNo;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_create_pin_persistent);

		/*
		if (savedInstanceState == null) {
			getFragmentManager().beginTransaction()
					.add(R.id.container, new PlaceholderFragment()).commit();
		}
		*/
		
		// Get the message from the intent
	    Intent intent = getIntent();
	    pinNo = intent.getByteExtra(MainActivity.PIN_NO, (byte) 1);
	    char[] setPinNoChars = ("Set PIN No.: " + pinNo).toCharArray();
	    
	    TextView setPinNo = (TextView) findViewById(R.id.set_pin_no);
	    setPinNo.setText(setPinNoChars, 0, setPinNoChars.length);
	    
	    /*Get random unblock PIN. Code inspired by
	    http://stackoverflow.com/questions/41107/how-to-generate-a-random-alpha-numeric-string?lq=1*/
	    String UPIN = "";
	    String alphaNum = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
		SecureRandom sr = new SecureRandom();
		for(int i = 0; i < 8; i++){
			UPIN = UPIN + alphaNum.charAt(sr.nextInt(alphaNum.length()));
		}
	    
		//Set it to the EditText fields
	    EditText newUnblockValue = (EditText) findViewById(R.id.get_new_unblock_value);
    	newUnblockValue.setText(UPIN.toCharArray(), 0, UPIN.length());
    	EditText newUnblockValueAgain = (EditText) findViewById(R.id.get_new_unblock_value_again);
    	newUnblockValueAgain.setText(UPIN.toCharArray(), 0, UPIN.length());
    	
    	TextView errorMessages = (TextView) findViewById(R.id.set_pin_error_messages);
	    char[] userAdvice = ("Minimum PIN length: 4, Maximum PIN length: 8. Write down Unblock PIN "
	    		+ "and hide it in a secret place or change it to your needs. Unblock PIN will be "
	    		+ "(if you do not change it): " + UPIN).toCharArray();
	    errorMessages.setText(userAdvice, 0, userAdvice.length);
    	
    	//Set Max attempts
    	EditText maxAttempts = (EditText) findViewById(R.id.get_max_attempts);
    	maxAttempts.setText("3".toCharArray(), 0, "3".length());
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.create_pin_persistent, menu);
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
			/*View rootView = inflater.inflate(R.layout.fragment_create_pin_persistent,
					container, false);
			return rootView;*/
			return null;
		}
	}

	public void setIt(View view){
		EditText newPin = (EditText) findViewById(R.id.get_new_pin);
    	String newPinString = newPin.getText().toString();
    	EditText newPinAgain = (EditText) findViewById(R.id.get_new_pin_again);
    	String newPinAgainString = newPinAgain.getText().toString();
    	EditText newUnblockValue = (EditText) findViewById(R.id.get_new_unblock_value);
    	String newUnblockValueString = newUnblockValue.getText().toString();
    	EditText newUnblockValueAgain = (EditText) findViewById(R.id.get_new_unblock_value_again);
    	String newUnblockValueAgainString = newUnblockValueAgain.getText().toString();
    	EditText maxAttempts = (EditText) findViewById(R.id.get_max_attempts);
    	int maxAttemptsNumber = Integer.parseInt(maxAttempts.getText().toString());
    	String error = null;
    	if(!newPinString.equals(newPinAgainString))
    		error = "PINs do not match!";
    	if(!newUnblockValueString.equals(newUnblockValueAgainString))
    		error = "Unblock values do not match!";
    	if(newPinString.length() < 4 || newPinString.length() > 8)
    		error = "Wrong PIN length! Minimal length: 4, Maximal length: 8";
    	if(newUnblockValueString.length() < 4 || newUnblockValueString.length() > 8)
    		error = "Wrong Unblock PIN length! Minimal length: 4, Maximal length: 8";
    		
    	TextView errorMessages = (TextView) findViewById(R.id.set_pin_error_messages);
    	if(error != null){
    		char[] errorAsChars = error.toCharArray();
    		errorMessages.setText(errorAsChars, 0, errorAsChars.length);
    	}else{
    		MSCHostApduService MyHostApduService = MSCHostApduService.getInstance(this);
    		MyHostApduService.createPinPersistent((byte) pinNo, (byte) maxAttemptsNumber, newPinString, newUnblockValueString);
    		char[] success = "PIN successfully set.".toCharArray();
    		errorMessages.setText(success, 0, success.length);
    		Intent resultIntent = new Intent();
    		setResult(Activity.RESULT_OK, resultIntent);
    		finish();
    	}
	}
}
