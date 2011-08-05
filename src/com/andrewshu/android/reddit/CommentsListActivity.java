/*
 * Copyright 2009 Andrew Shu
 *
 * This file is part of "reddit is fun".
 *
 * "reddit is fun" is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * "reddit is fun" is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with "reddit is fun".  If not, see <http://www.gnu.org/licenses/>.
 */

package com.andrewshu.android.reddit;


import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.ObjectMapper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telephony.PhoneNumberUtils;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieSyncManager;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.andrewshu.android.reddit.ThreadsListActivity.ThumbnailOnClickListenerFactory;


/**
 * Main Activity class representing a Subreddit, i.e., a ThreadsList.
 * 
 * @author TalkLittle
 *
 */
public class CommentsListActivity extends ListActivity
		implements View.OnCreateContextMenuListener {

	private static final String TAG = "CommentsListActivity";
	
    // Group 2: subreddit name. Group 3: thread id36. Group 4: Comment id36.
    private final Pattern COMMENT_PATH_PATTERN = Pattern.compile(Constants.COMMENT_PATH_PATTERN_STRING);
    private final Pattern COMMENT_CONTEXT_PATTERN = Pattern.compile("context=(\\d+)");

    private final ObjectMapper mObjectMapper = Common.getObjectMapper();
    private final BitmapManager drawableManager = new BitmapManager();
    private final Markdown markdown = new Markdown();
    
    /** Custom list adapter that fits our threads data into the list. */
    private CommentsListAdapter mCommentsAdapter = null;
    private ArrayList<ThingInfo> mCommentsList = null;
    // Lock used when modifying the mCommentsAdapter
    private static final Object COMMENT_ADAPTER_LOCK = new Object();
    
    private final DefaultHttpClient mClient = Common.getGzipHttpClient();
    
    
    // Common settings are stored here
    private final RedditSettings mSettings = new RedditSettings();
    
    private String mSubreddit = null;
    private String mThreadId = null;
    private ThingInfo mOpThingInfo = null;
    private String mThreadTitle = null;
	
    // UI State
    private ThingInfo mVoteTargetThing = null;
    private String mReportTargetName = null;
    private String mReplyTargetName = null;
    private String mEditTargetBody = null;
    private String mDeleteTargetKind = null;
    private boolean mShouldClearReply = false;
    private AsyncTask<?, ?, ?> mCurrentDownloadCommentsTask = null;
    private static final Object mCurrentDownloadCommentsTaskLock = new Object();

    private String last_search_string;
    private int last_found_position = -1;
    private int translucent_yellow;
    
    private boolean mCanChord = false;
    
    // override transition animation available Android 2.0 (SDK Level 5) and above
    private static Method mActivity_overridePendingTransition;
    
    static {
        initCompatibility();
    };

    private static void initCompatibility() {
        try {
            mActivity_overridePendingTransition = Activity.class.getMethod(
                    "overridePendingTransition", new Class[] { Integer.TYPE, Integer.TYPE } );
            /* success, this is a newer device */
        } catch (NoSuchMethodException nsme) {
            /* failure, must be older device */
        }
    }
    
    /**
     * Called when the activity starts up. Do activity initialization
     * here, not in a constructor.
     * 
     * @see Activity#onCreate
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        translucent_yellow = getResources().getColor(R.color.translucent_yellow);
        
		CookieSyncManager.createInstance(getApplicationContext());
		
		mSettings.loadRedditPreferences(this, mClient);

        setRequestedOrientation(mSettings.rotation);
        setTheme(mSettings.theme);
        requestWindowFeature(Window.FEATURE_PROGRESS);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    	
        setContentView(R.layout.comments_list_content);
        
        if (savedInstanceState != null) {
        	mReplyTargetName = savedInstanceState.getString(Constants.REPLY_TARGET_NAME_KEY);
        	mReportTargetName = savedInstanceState.getString(Constants.REPORT_TARGET_NAME_KEY);
        	mEditTargetBody = savedInstanceState.getString(Constants.EDIT_TARGET_BODY_KEY);
        	mDeleteTargetKind = savedInstanceState.getString(Constants.DELETE_TARGET_KIND_KEY);
        	mThreadTitle = savedInstanceState.getString(Constants.THREAD_TITLE_KEY);
        	mSubreddit = savedInstanceState.getString(Constants.SUBREDDIT_KEY);
        	mThreadId = savedInstanceState.getString(Constants.THREAD_ID_KEY);
        	
        	if (mThreadTitle != null) {
        	    setTitle(mThreadTitle + " : " + mSubreddit);
        	}
        	
		    CommentsRetainer retainer = (CommentsRetainer) getLastNonConfigurationInstance();
        	if (retainer == null) {
		    	new DownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
		    } else {
		    	// Orientation change. Use prior instance.
		    	mOpThingInfo = retainer.opThingInfo;
			    mCommentsList = retainer.commentsList;
			    resetUI(new CommentsListAdapter(this, mCommentsList));
		    }
    	}
        
        // No saved state; use info from Intent.getData()
        else {
        	String commentPath;
        	String commentQuery;
        	String jumpToCommentId = null;
        	int jumpToCommentContext = 0;
    		// We get the URL through getIntent().getData()
            Uri data = getIntent().getData();
            if (data != null) {
            	// Comment path: a URL pointing to a thread or a comment in a thread.
            	commentPath = data.getPath();
            	commentQuery = data.getQuery();
            } else {
        		if (Constants.LOGGING) Log.e(TAG, "Quitting because no subreddit and thread id data was passed into the Intent.");
        		finish();
        		return;
            }
            
        	if (commentPath != null) {
        		if (Constants.LOGGING) Log.d(TAG, "comment path: "+commentPath);
        		
        		if (Util.isRedditShortenedUri(data)) {
        			// http://redd.it/abc12
        			mThreadId = commentPath.substring(1);
        		} else {
        			// http://www.reddit.com/...
	        		Matcher m = COMMENT_PATH_PATTERN.matcher(commentPath);
	        		if (m.matches()) {
	            		mSubreddit = m.group(1);
	        			mThreadId = m.group(2);
	        			jumpToCommentId = m.group(3);
	        		}
        		}
        	} else {
    			if (Constants.LOGGING) Log.e(TAG, "Quitting because of bad comment path.");
    			finish();
    			return;
    		}
        	
        	if (commentQuery != null) {
        		Matcher m = COMMENT_CONTEXT_PATTERN.matcher(commentQuery);
        		if (m.find()) {
        			jumpToCommentContext = m.group(1) != null ? Integer.valueOf(m.group(1)) : 0;
        		}
        	}
        	
        	// Extras: subreddit, threadTitle, numComments
        	// subreddit is not always redundant to Intent.getData(),
        	// since URL does not always contain the subreddit. (e.g., self posts)
        	Bundle extras = getIntent().getExtras();
        	if (extras != null) {
        		// subreddit could have already been set from the Intent.getData. don't overwrite with null here!
        		String subreddit = extras.getString(Constants.EXTRA_SUBREDDIT);
        		if (subreddit != null)
        			mSubreddit = subreddit;
        		// mThreadTitle has not been set yet, so no need for null check before setting it
        		mThreadTitle = extras.getString(Constants.EXTRA_TITLE);
        		if (mThreadTitle != null) {
            	    setTitle(mThreadTitle + " : " + mSubreddit);
            	}
        		// TODO: use extras.getInt(Constants.EXTRA_NUM_COMMENTS) somehow
        	}
        	
        	if (!Util.isEmpty(jumpToCommentId)) {
        		new DownloadCommentsTask().prepareLoadAndJumpToComment(jumpToCommentId, jumpToCommentContext)
        				.execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
        	}
        	else {
        		new DownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
        	}
        }
    }
    
    @Override
    protected void onResume() {
    	super.onResume();
		CookieSyncManager.getInstance().startSync();
    	int previousTheme = mSettings.theme;
    	mSettings.loadRedditPreferences(this, mClient);
    	setRequestedOrientation(mSettings.rotation);
    	if (mSettings.theme != previousTheme) {
    		resetUI(mCommentsAdapter);
    	}

    	new PeekEnvelopeTask(this, mClient, mSettings.mailNotificationStyle).execute();
    }
    
    @Override
    protected void onPause() {
    	super.onPause();
		CookieSyncManager.getInstance().stopSync();
		mSettings.saveRedditPreferences(this);
    }
    
    @Override
    public Object onRetainNonConfigurationInstance() {
        // Avoid having to re-download and re-parse the comments list
    	// when rotating or opening keyboard.
    	if (mOpThingInfo != null && mCommentsList != null)
    		return new CommentsRetainer(mOpThingInfo, mCommentsList);
    	else
    		return null;
    }
    
    class CommentsRetainer {
    	public ArrayList<ThingInfo> commentsList;
    	public ThingInfo opThingInfo;
    	
    	public CommentsRetainer(ThingInfo op, ArrayList<ThingInfo> comments) {
    		this.opThingInfo = op;
    		this.commentsList = comments;
    	}
    }
    
    private boolean isHiddenCommentHeadPosition(int position) {
    	return mCommentsAdapter != null && mCommentsAdapter.getItemViewType(position) == CommentsListAdapter.HIDDEN_ITEM_HEAD_VIEW_TYPE;
    }
    
    private boolean isHiddenCommentDescendantPosition(int position) {
    	return mCommentsAdapter != null && mCommentsAdapter.getItem(position).isHiddenCommentDescendant();
    }
    
    private boolean isLoadMoreCommentsPosition(int position) {
    	return mCommentsAdapter != null && mCommentsAdapter.getItemViewType(position) == CommentsListAdapter.MORE_ITEM_VIEW_TYPE;
    }
    
    private final class CommentsListAdapter extends ArrayAdapter<ThingInfo> {
    	public static final int OP_ITEM_VIEW_TYPE = 0;
    	public static final int COMMENT_ITEM_VIEW_TYPE = 1;
    	public static final int MORE_ITEM_VIEW_TYPE = 2;
    	public static final int HIDDEN_ITEM_HEAD_VIEW_TYPE = 3;
    	// The number of view types
    	public static final int VIEW_TYPE_COUNT = 4;
    	
    	public boolean mIsLoading = true;
    	
    	private LayoutInflater mInflater;
        private int mFrequentSeparatorPos = ListView.INVALID_POSITION;
        
        public CommentsListAdapter(Context context, List<ThingInfo> objects) {
            super(context, 0, objects);
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public int getItemViewType(int position) {
        	if (position == 0)
        		return OP_ITEM_VIEW_TYPE;
        	if (position == mFrequentSeparatorPos) {
                // We don't want the separator view to be recycled.
                return IGNORE_ITEM_VIEW_TYPE;
            }
        	
            ThingInfo item = getItem(position);
            if (item.isHiddenCommentDescendant())
            	return IGNORE_ITEM_VIEW_TYPE;
            if (item.isHiddenCommentHead())
            	return HIDDEN_ITEM_HEAD_VIEW_TYPE;
            if (item.isLoadMoreCommentsPlaceholder())
            	return MORE_ITEM_VIEW_TYPE;
            
            return COMMENT_ITEM_VIEW_TYPE;
        }
        
        @Override
        public int getViewTypeCount() {
        	return VIEW_TYPE_COUNT;
        }
        
        @Override
        public boolean isEmpty() {
        	if (mIsLoading)
        		return false;
        	return super.isEmpty();
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            
            ThingInfo item = this.getItem(position);
            
            try {
	            if (position == 0) {
	            	// The OP
	            	if (view == null) {
	            		view = mInflater.inflate(R.layout.threads_list_item, null);
	            	}
	            	
	            	ThreadsListActivity.fillThreadsListItemView(view, item, CommentsListActivity.this,
	                		mSettings, drawableManager, false, thumbnailOnClickListenerFactory);
	                
	                // In addition to stuff from ThreadsListActivity,
	            	// we want to show selftext in CommentsListActivity.
	                
	            	TextView submissionStuffView = (TextView) view.findViewById(R.id.submissionTime_submitter);
	                TextView selftextView = (TextView) view.findViewById(R.id.selftext);
	                
	                submissionStuffView.setVisibility(View.VISIBLE);
	                submissionStuffView.setText(
	                		String.format(getResources().getString(R.string.thread_time_submitter),
	                				Util.getTimeAgo(item.getCreated_utc()), item.getAuthor()));
	                
	            	if (!Util.isEmpty(item.getSpannedSelftext())) {
	            		selftextView.setVisibility(View.VISIBLE);
		                selftextView.setText(item.getSpannedSelftext());
	            	} else {
	            		selftextView.setVisibility(View.GONE);
	            	}
	            	
	            } else if (isHiddenCommentDescendantPosition(position)) { 
	            	if (view == null) {
	            		// Doesn't matter which view we inflate since it's gonna be invisible
	            		view = mInflater.inflate(R.layout.zero_size_layout, null);
	            	}
	            } else if (isHiddenCommentHeadPosition(position)) {
	            	if (view == null) {
	            		view = mInflater.inflate(R.layout.comments_list_item_hidden, null);
	            	}
	            	TextView votesView = (TextView) view.findViewById(R.id.votes);
		            TextView submitterView = (TextView) view.findViewById(R.id.submitter);
	                TextView submissionTimeView = (TextView) view.findViewById(R.id.submissionTime);
		            
		            try {
		            	votesView.setText(Util.showNumPoints(item.getUps() - item.getDowns()));
		            } catch (NumberFormatException e) {
		            	// This happens because "ups" comes after the potentially long "replies" object,
		            	// so the ListView might try to display the View before "ups" in JSON has been parsed.
		            	if (Constants.LOGGING) Log.e(TAG, "getView, hidden comment heads", e);
		            }
		            if (mOpThingInfo != null && item.getAuthor().equalsIgnoreCase(mOpThingInfo.getAuthor()))
		            	submitterView.setText(item.getAuthor() + " [S]");
		            else
		            	submitterView.setText(item.getAuthor());
		            submissionTimeView.setText(Util.getTimeAgo(item.getCreated_utc()));
		            
		            setCommentIndent(view, item.getIndent(), mSettings);
		            
            	} else if (isLoadMoreCommentsPosition(position)) {
	            	// "load more comments"
	            	if (view == null) {
	            		view = mInflater.inflate(R.layout.more_comments_view, null);
	            	}

		            // Set colors based on theme
//	            	final TextView moreCommentsText = (TextView) view.findViewById(R.id.more_comments_text);
//	            	if (Util.isLightTheme(mSettings.theme)) {
//		            	view.setBackgroundResource(R.color.light_light_gray);
//		            	moreCommentsText.setBackgroundResource(R.color.white);
//		            } else {
//		            	view.setBackgroundResource(R.color.dark_dark_gray);
//		            	moreCommentsText.setBackgroundResource(android.R.color.background_dark);
//		            }

	            	setCommentIndent(view, item.getIndent(), mSettings);
	            	
	            } else {  // Regular comment
	            	// Here view may be passed in for re-use, or we make a new one.
		            if (view == null) {
		                view = mInflater.inflate(R.layout.comments_list_item, null);
		            } else {
		                view = convertView;
		            }

					// Sometimes (when in touch mode) the "selection" highlight disappears.
					// So we make our own persistent highlight. This background color must
					// be set explicitly on every element, however, or the "cached" list
					// item views will show up with the color.
					view.setBackgroundColor(position == last_found_position ? translucent_yellow : Color.TRANSPARENT);

		            fillCommentsListItemView(view, item, mSettings);
	            }
            } catch (NullPointerException e) {
            	if (Constants.LOGGING) Log.w(TAG, "NPE in getView()", e);
            	// Probably means that the List is still being built, and OP probably got put in wrong position
            	if (view == null) {
            		if (position == 0)
            			view = mInflater.inflate(R.layout.threads_list_item, null);
            		else
            			view = mInflater.inflate(R.layout.comments_list_item, null);
	            }
            }
            return view;
        }
    } // End of CommentsListAdapter

    public static void setCommentIndent(View commentListItemView, int indentLevel, RedditSettings settings) {
        View[] indentViews = new View[] {
        	commentListItemView.findViewById(R.id.left_indent1),
        	commentListItemView.findViewById(R.id.left_indent2),
        	commentListItemView.findViewById(R.id.left_indent3),
        	commentListItemView.findViewById(R.id.left_indent4),
        	commentListItemView.findViewById(R.id.left_indent5),
        	commentListItemView.findViewById(R.id.left_indent6),
        	commentListItemView.findViewById(R.id.left_indent7),
        	commentListItemView.findViewById(R.id.left_indent8)
        };
        for (int i = 0; i < indentLevel && i < indentViews.length; i++) {
        	if (settings.showCommentGuideLines) {
            	indentViews[i].setVisibility(View.VISIBLE);
            	if (Util.isLightTheme(settings.theme)) {
            		indentViews[i].setBackgroundResource(R.color.light_light_gray);
            	} else {
            		indentViews[i].setBackgroundResource(R.color.dark_gray);
            	}
        	} else {
        		indentViews[i].setVisibility(View.INVISIBLE);
        	}
        }
        for (int i = indentLevel; i < indentViews.length; i++) {
        	indentViews[i].setVisibility(View.GONE);
        }
    }

    
    /**
     * Called when user clicks an item in the list. Starts an activity to
     * open the url for that item.
     */
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        ThingInfo item = mCommentsAdapter.getItem(position);
        
        if (isHiddenCommentHeadPosition(position)) {
        	showComment(position);
        	return;
        }
        
        // Mark the OP post/regular comment as selected
        mVoteTargetThing = item;
        mReplyTargetName = mVoteTargetThing.getName();
		
        if (isLoadMoreCommentsPosition(position)) {
        	// Use this constructor to tell it to load more comments inline
        	new DownloadCommentsTask().prepareLoadMoreComments(item.getId(), position, item.getIndent())
        			.execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
        } else {
        	if (!"[deleted]".equals(item.getAuthor()))
        		showDialog(Constants.DIALOG_COMMENT_CLICK);
        }
    }
    
    /**
     * Resets the output UI list contents, retains session state.
     * @param commentsAdapter A new CommentsListAdapter to use. Pass in null to create a new empty one.
     */
    public void resetUI(CommentsListAdapter commentsAdapter) {
    	int firstVisiblePosition = getListView().getFirstVisiblePosition();
    	
    	setTheme(mSettings.theme);
    	setContentView(R.layout.comments_list_content);
        registerForContextMenu(getListView());

        synchronized (COMMENT_ADAPTER_LOCK) {
	    	if (commentsAdapter == null) {
	    		// Reset the list to be empty.
	    		mCommentsList = new ArrayList<ThingInfo>();
	            mCommentsAdapter = new CommentsListAdapter(this, mCommentsList);
	    	} else {
	    		mCommentsAdapter = commentsAdapter;
	    	}
	        setListAdapter(mCommentsAdapter);
	        mCommentsAdapter.mIsLoading = false;
	        mCommentsAdapter.notifyDataSetChanged();  // Just in case
    	}
        getListView().setDivider(null);
        Common.updateListDrawables(this, mSettings.theme);
        
        getListView().setSelection(firstVisiblePosition);
    }
    
    /**
     * Mark the OP submitter comments
     */
    private void markSubmitterComments() {
    	if (mOpThingInfo == null || mCommentsAdapter == null)
    		return;
    	
		SpannableString authorSS = new SpannableString(mOpThingInfo.getAuthor() + " [S]");
        ForegroundColorSpan fcs;
        if (Util.isLightTheme(mSettings.theme))
        	fcs = new ForegroundColorSpan(getResources().getColor(R.color.blue));
        else
        	fcs = new ForegroundColorSpan(getResources().getColor(R.color.pale_blue));
        authorSS.setSpan(fcs, 0, authorSS.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	
        synchronized (COMMENT_ADAPTER_LOCK) {
    		for (int i = 0; i < mCommentsAdapter.getCount(); i++) {
    			ThingInfo ci = mCommentsAdapter.getItem(i);
    			// if it's the OP, mark his name
    			if (mOpThingInfo.getAuthor().equalsIgnoreCase(ci.getAuthor()))
    	            ci.setSSAuthor(authorSS);
    		}
    	}
    }
    
    /**
     * Task takes in a subreddit name string and thread id, downloads its data, parses
     * out the comments, and communicates them back to the UI as they are read.
     * 
     * Requires the following navigation variables to be set:
     * mSettings.subreddit
     * mSettings.threadId
     * mMoreChildrenId (can be "")
     * mSortByUrl
     */
    class DownloadCommentsTask extends AsyncTask<Integer, Long, Boolean>
    		implements PropertyChangeListener {
    	
    	private static final String TAG = "CommentsListActivity.DownloadCommentsTask";
    	
    	// offset of the first comment being loaded; 0 if it includes OP
    	private int _mPositionOffset = 0;
    	private int _mIndentation = 0;
    	private String _mMoreChildrenId = "";

    	private LinkedList<ThingInfo> _mDeferredInsertList = new LinkedList<ThingInfo>();
    	private LinkedList<DeferredCommentProcessing> _mDeferredProcessingList = new LinkedList<DeferredCommentProcessing>();
    	
    	// Progress bar
    	private long _mContentLength = 0;
    	
    	private String _mJumpToCommentId = "";
    	private ThingInfo[] _mJumpToCommentContext = new ThingInfo[0];
    	private int _mJumpToCommentContextIndex = 0;  // keep track of insertion index, act like circular array overwriting
    	private boolean _mIsFoundJumpTargetComment = false;
    	
    	private class DeferredCommentProcessing {
    		public int commentIndex;
    		public ThingInfo comment;
    		public DeferredCommentProcessing(ThingInfo comment, int commentIndex) {
    			this.comment = comment;
    			this.commentIndex = commentIndex;
    		}
    	}
       
    	/**
    	 * Default constructor to do normal comments page
    	 */
    	public DownloadCommentsTask() {
    	}
    	
    	/**
    	 * "load more comments" starting at this position
    	 * @param moreChildrenId The reddit thing-id of the "more" children comment
    	 * @param morePosition Position in local list to insert
    	 * @param indentation The indentation level of the child.
    	 */
    	public DownloadCommentsTask prepareLoadMoreComments(String moreChildrenId, int morePosition, int indentation) {
    		_mMoreChildrenId = moreChildrenId;
    		_mPositionOffset = morePosition;
    		_mIndentation = indentation;
    		return this;
    	}
    	
    	public DownloadCommentsTask prepareLoadAndJumpToComment(String commentId, int context) {
    		_mJumpToCommentId = commentId;
    		_mJumpToCommentContext = new ThingInfo[context];
    		return this;
    	}
    	
    	// XXX: maxComments is unused for now
    	public Boolean doInBackground(Integer... maxComments) {
    		HttpEntity entity = null;
            try {
            	StringBuilder sb = new StringBuilder("http://api.reddit.com");
        		if (mSubreddit != null) {
        			sb.append("/r/").append(mSubreddit.trim());
        		}
        		sb.append("/comments/")
	        		.append(mThreadId)
	        		.append("/z/").append(_mMoreChildrenId).append("/?")
	        		.append(mSettings.commentsSortByUrl).append("&");
	        	if (_mJumpToCommentContext.length != 0)
	        		sb.append("context=").append(_mJumpToCommentContext.length).append("&");
	        	
	        	String url = sb.toString();
	        	
	        	InputStream in = null;
	    		boolean currentlyUsingCache = false;
	    		
	        	if (Constants.USE_COMMENTS_CACHE) {
	    			try {
		    			if (CacheInfo.checkFreshThreadCache(getApplicationContext())
		    					&& url.equals(CacheInfo.getCachedThreadUrl(getApplicationContext()))) {
		    				in = openFileInput(Constants.FILENAME_THREAD_CACHE);
		    				_mContentLength = getFileStreamPath(Constants.FILENAME_THREAD_CACHE).length();
		    				currentlyUsingCache = true;
		    				if (Constants.LOGGING) Log.d(TAG, "Using cached thread JSON, length=" + _mContentLength);
		    			}
	    			} catch (Exception cacheEx) {
	    				if (Constants.LOGGING) Log.w(TAG, "skip cache", cacheEx);
	    			}
	    		}
	    		
	    		// If we couldn't use the cache, then do HTTP request
	        	if (!currentlyUsingCache) {
			    	HttpGet request = new HttpGet(url);
	                HttpResponse response = mClient.execute(request);
	            	
	                // Read the header to get Content-Length since entity.getContentLength() returns -1
	            	Header contentLengthHeader = response.getFirstHeader("Content-Length");
	            	if (contentLengthHeader != null) {
	            		_mContentLength = Long.valueOf(contentLengthHeader.getValue());
		            	if (Constants.LOGGING) Log.d(TAG, "Content length: "+_mContentLength);
	            	}
	            	else {
	            		_mContentLength = -1; 
		            	if (Constants.LOGGING) Log.d(TAG, "Content length: UNAVAILABLE");
	            	}
	
	            	entity = response.getEntity();
	            	in = entity.getContent();
	            	
	            	if (Constants.USE_COMMENTS_CACHE) {
	                	in = CacheInfo.writeThenRead(getApplicationContext(), in, Constants.FILENAME_THREAD_CACHE);
	                	try {
	                		CacheInfo.setCachedThreadUrl(getApplicationContext(), url);
	                	} catch (IOException e) {
	                		if (Constants.LOGGING) Log.e(TAG, "error on setCachedThreadId", e);
	                	}
	            	}
	        	}
                
            	// setup a special InputStream to report progress
            	ProgressInputStream pin = new ProgressInputStream(in, _mContentLength);
            	pin.addPropertyChangeListener(this);
            	
            	parseCommentsJSON(pin);
            	if (Constants.LOGGING) Log.d(TAG, "parseCommentsJSON completed");
            	
            	pin.close();
                in.close();
                
                // label the OP's comments with [S]
                markSubmitterComments();
				
                return true;
                
            } catch (Exception e) {
            	if (Constants.LOGGING) Log.e(TAG, "DownloadCommentsTask", e);
            } finally {
        		if (entity != null) {
        			try {
        				entity.consumeContent();
        			} catch (Exception e2) {
        				if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
        			}
        		}
            }
            return false;
	    }
    	
    	private void appendComment(final ThingInfo comment) {
    		runOnUiThread(new Runnable() {
    			@Override
    			public void run() {
    	    		synchronized (COMMENT_ADAPTER_LOCK) {
    	    			mCommentsList.add(comment);
    	    		}
	    			mCommentsAdapter.notifyDataSetChanged();
    			}
    		});
    	}
    	
    	private void replaceCommentsAtPosition(final Collection<ThingInfo> comments, final int position) {
    		runOnUiThread(new Runnable() {
    			@Override
    			public void run() {
    	    		synchronized (COMMENT_ADAPTER_LOCK) {
    					mCommentsList.remove(position);
    	 	    		mCommentsList.addAll(position, comments);
    	    		}
    				mCommentsAdapter.notifyDataSetChanged();
    			}
    		});
    	}
    	
    	/**
    	 * defer insertion of comment, in case we want to insert a group of comments at the same time for convenience.
    	 */
    	private void deferCommentInsertion(ThingInfo comment) {
    		_mDeferredInsertList.add(comment);
    	}
    	
    	/**
    	 * defer the slow processing step of a comment, in case we want to prioritize processing of comments over others.
    	 */
    	private void deferCommentProcessing(ThingInfo comment, int commentIndex) {
    		_mDeferredProcessingList.add(new DeferredCommentProcessing(comment, commentIndex));
    	}
    	
    	/**
    	 * tell if inserting entire thread, versus loading "more comments"
    	 */
    	private boolean isInsertingEntireThread() {
    		return _mPositionOffset == 0;
    	}
    	
    	private void disableLoadingScreenKeepProgress() {
    		runOnUiThread(new Runnable() {
    			@Override
    			public void run() {
    	    		resetUI(mCommentsAdapter);
    			}
    		});
    	}
    	
    	private void parseCommentsJSON(
    			InputStream in
		) throws IOException, JsonParseException {
    		int insertedCommentIndex;
			String genericListingError = "Not a comments listing";
			try {
				Listing[] listings = mObjectMapper.readValue(in, Listing[].class);

				// listings[0] is a thread Listing for the OP.
				// process same as a thread listing more or less
				
				Util.assertState(Constants.JSON_LISTING.equals(listings[0].getKind()), genericListingError);
				
				// Save modhash, ignore "after" and "before" which are meaningless in this context (and probably null)
				ListingData threadListingData = listings[0].getData();
				if (Util.isEmpty(threadListingData.getModhash()))
					mSettings.setModhash(null);
				else
					mSettings.setModhash(threadListingData.getModhash());
				
				if (Constants.LOGGING) Log.d(TAG, "Successfully got OP listing[0]: modhash "+mSettings.modhash);
				
				ThingListing threadThingListing = threadListingData.getChildren()[0];
				Util.assertState(Constants.THREAD_KIND.equals(threadThingListing.getKind()), genericListingError);

				if (isInsertingEntireThread()) {
					parseOP(threadThingListing.getData());
					insertedCommentIndex = 0;  // we just inserted the OP into position 0
				}
				else {
					insertedCommentIndex = _mPositionOffset - 1;  // -1 because we +1 for the first comment
				}
				
				// at this point we've started displaying comments, so disable the loading screen
				disableLoadingScreenKeepProgress();
				
				// listings[1] is a comment Listing for the comments
				// Go through the children and get the ThingInfos
				ListingData commentListingData = listings[1].getData();
				for (ThingListing commentThingListing : commentListingData.getChildren()) {
					// insert the comment and its replies, prefix traversal order
					insertedCommentIndex = insertNestedComment(commentThingListing, 0, insertedCommentIndex + 1);
				}
				
				processDeferredComments();
				
			} catch (Exception ex) {
				if (Constants.LOGGING) Log.e(TAG, "parseCommentsJSON", ex);
			}
		}
    	
    	private void parseOP(ThingInfo data) {
			mOpThingInfo = data;
			mOpThingInfo.setIndent(0);
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					synchronized (COMMENT_ADAPTER_LOCK) {
						mCommentsList.add(0, mOpThingInfo);
					}
				}
			});

			if (mOpThingInfo.isIs_self() && mOpThingInfo.getSelftext_html() != null) {
				// HTML to Spanned
				String unescapedHtmlSelftext = Html.fromHtml(mOpThingInfo.getSelftext_html()).toString();
				Spanned selftext = Html.fromHtml(Util.convertHtmlTags(unescapedHtmlSelftext));
				
	    		// remove last 2 newline characters
				if (selftext.length() > 2)
					mOpThingInfo.setSpannedSelftext(selftext.subSequence(0, selftext.length()-2));
				else
					mOpThingInfo.setSpannedSelftext("");

				// Get URLs from markdown
				markdown.getURLs(mOpThingInfo.getSelftext(), mOpThingInfo.getUrls());
			}
			// We might not have a title if we've intercepted a plain link to a thread.
			mThreadTitle = mOpThingInfo.getTitle();
			mSubreddit = mOpThingInfo.getSubreddit();
			mThreadId = mOpThingInfo.getId();
    	}
    	
    	/**
    	 * Recursive method to insert comment tree into the mCommentsList,
    	 * with proper list order and indentation
    	 */
    	int insertNestedComment(ThingListing commentThingListing, int indentLevel, int insertedCommentIndex) {
    		ThingInfo ci = commentThingListing.getData();
    		ci.setIndent(_mIndentation + indentLevel);
    		
    		if (isHasJumpTarget()) {
    			if (_mJumpToCommentId.equals(ci.getId()))
    				processJumpTarget(ci, insertedCommentIndex);
    			else if (!_mIsFoundJumpTargetComment)
    				addJumpTargetContext(ci);
    		}

    		if (isShouldDoSlowProcessing())
	    		processCommentSlowSteps(ci);
    		else
    			deferCommentProcessing(ci, insertedCommentIndex);

    		// Insert the comment
    		if (isInsertingEntireThread())
				appendComment(ci);
    		else
    			deferCommentInsertion(ci);
    		
    		// handle "more" entry
    		if (Constants.MORE_KIND.equals(commentThingListing.getKind())) {
    			ci.setLoadMoreCommentsPlaceholder(true);
    			if (Constants.LOGGING) Log.v(TAG, "new more position at " + (insertedCommentIndex));
		    	return insertedCommentIndex;
    		}
    		
    		// Regular comment
    		
    		// Skip things that are not comments, which shouldn't happen
			if (!Constants.COMMENT_KIND.equals(commentThingListing.getKind())) {
				if (Constants.LOGGING) Log.e(TAG, "comment whose kind is \""+commentThingListing.getKind()+"\" (expected "+Constants.COMMENT_KIND+")");
				return insertedCommentIndex;
			}
			
			// handle the replies
			Listing repliesListing = ci.getReplies();
			if (repliesListing == null)
				return insertedCommentIndex;
			ListingData repliesListingData = repliesListing.getData();
			if (repliesListingData == null)
				return insertedCommentIndex;
			ThingListing[] replyThingListings = repliesListingData.getChildren();
			if (replyThingListings == null)
				return insertedCommentIndex;
			
			for (ThingListing replyThingListing : replyThingListings) {
				insertedCommentIndex = insertNestedComment(replyThingListing, indentLevel + 1, insertedCommentIndex + 1);
			}
			return insertedCommentIndex;
    	}
    	
    	private boolean isHasJumpTarget() {
    		return ! Util.isEmpty(_mJumpToCommentId);
    	}
    	
    	private boolean isShouldDoSlowProcessing() {
    		return !isHasJumpTarget() || _mIsFoundJumpTargetComment;
    	}
    	
    	private void processJumpTarget(ThingInfo comment, int commentIndex) {
			_mIsFoundJumpTargetComment = true;
			int numContext = _mJumpToCommentContext.length;
			final int jumpTargetIndex = (commentIndex - numContext) > 0 ? (commentIndex - numContext) : 0;
			
			// load the comments that are the context of the target comment
			for (ThingInfo contextComment : _mJumpToCommentContext) {
				if (contextComment == null)
					break;
				processCommentSlowSteps(contextComment);
			}
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					refreshVisibleComments();
					getListView().setSelection(jumpTargetIndex);
				}
			});
    	}
    	
    	private void addJumpTargetContext(ThingInfo comment) {
    		_mJumpToCommentContext[_mJumpToCommentContextIndex] = comment;
    		_mJumpToCommentContextIndex = (_mJumpToCommentContextIndex + 1) % _mJumpToCommentContext.length;
    	}
    	
    	private void processCommentSlowSteps(ThingInfo comment) {
    		if (comment.getBody_html() != null) {
	        	CharSequence spanned = createSpanned(comment.getBody_html());
	        	comment.setSpannedBody(spanned);
    		}
    		markdown.getURLs(comment.getBody(), comment.getUrls());
    	}
    	
    	private void processDeferredComments() {
        	if (!_mDeferredInsertList.isEmpty()) {
        		replaceCommentsAtPosition(_mDeferredInsertList, _mPositionOffset);
        	}
            
        	if (!_mDeferredProcessingList.isEmpty()) {
        		for (final DeferredCommentProcessing deferredCommentProcessing : _mDeferredProcessingList) {
        			processCommentSlowSteps(deferredCommentProcessing.comment);
        			refreshCommentIfVisible(deferredCommentProcessing.commentIndex);
        		}
        	}
    	}
    	
    	private void refreshCommentIfVisible(final int commentIndex) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
        			if (isPositionVisible(commentIndex))
        				refreshCommentBodyTextView(commentIndex);
				}
			});
    	}
    	
    	/**
    	 * @param bodyHtml escaped HTML (like in reddit Thing's body_html)
    	 */
        private CharSequence createSpanned(String bodyHtml) {
        	try {
        		// get unescaped HTML
        		bodyHtml = Html.fromHtml(bodyHtml).toString();
        		// fromHtml doesn't support all HTML tags. convert <code> and <pre>
        		bodyHtml = Util.convertHtmlTags(bodyHtml);
        		
        		Spanned body = Html.fromHtml(bodyHtml);
        		// remove last 2 newline characters
        		if (body.length() > 2)
        			return body.subSequence(0, body.length()-2);
        		else
        			return "";
        	} catch (Exception e) {
        		if (Constants.LOGGING) Log.e(TAG, "createSpanned failed", e);
        		return null;
        	}
        }
        
        /**
         * cleanup deferred in onPostExecute(), otherwise you clear it too soon and end up with race condition vs. UI thread
         */
        private void cleanupDeferred() {
        	_mDeferredInsertList.clear();
        	_mDeferredProcessingList.clear();
        }
        
        @Override
    	public void onPreExecute() {
    		if (mThreadId == null) {
    			if (Constants.LOGGING) Log.e(TAG, "mSettings.threadId == null");
	    		this.cancel(true);
	    		return;
    		}
    		synchronized (mCurrentDownloadCommentsTaskLock) {
	    		if (mCurrentDownloadCommentsTask != null) {
	    			this.cancel(true);
	    			return;
	    		}
	    		mCurrentDownloadCommentsTask = this;
    		}
    		
    		if (isInsertingEntireThread()) {
        		// Initialize mCommentsList and mCommentsAdapter
        		synchronized (COMMENT_ADAPTER_LOCK) {
        			resetUI(null);
        		}
        		// Do loading screen when loading new thread; otherwise when "loading more comments" don't show it
    			enableLoadingScreen();
    		}
    		
    		if (_mContentLength == -1)
    			getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_INDETERMINATE_ON);

    		if (mThreadTitle != null)
	    		setTitle(mThreadTitle + " : " + mSubreddit);
    	}
    	
    	@Override
    	public void onPostExecute(Boolean success) {
    		cleanupDeferred();
    		synchronized (mCurrentDownloadCommentsTaskLock) {
    			mCurrentDownloadCommentsTask = null;
    		}
    		
    		if (_mContentLength == -1)
    			getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_INDETERMINATE_OFF);
    		else
    			getWindow().setFeatureInt(Window.FEATURE_PROGRESS, 10000);
    		
    		if (success) {
    			// We should clear any replies the user was composing.
    			mShouldClearReply = true;

    			refreshVisibleComments();
    			
    			// Set title in android titlebar
    			if (mThreadTitle != null)
    				setTitle(mThreadTitle + " : " + mSubreddit);
    		} else {
    			if (!isCancelled())
    				Common.showErrorToast("Error downloading comments. Please try again.", Toast.LENGTH_LONG, CommentsListActivity.this);
    		}
    	}
    	
    	@Override
    	public void onProgressUpdate(Long... progress) {
    		// 0-9999 is ok, 10000 means it's finished
    		if (_mContentLength == -1) {
    			getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_INDETERMINATE_ON);
    		}
    		else {
    			getWindow().setFeatureInt(Window.FEATURE_PROGRESS, progress[0].intValue() * 9999 / (int) _mContentLength);
    		}
    	}
    	
    	@Override
    	public void propertyChange(PropertyChangeEvent event) {
    		publishProgress((Long) event.getNewValue());
    	}
    }
    
    private void enableLoadingScreen() {
    	if (Util.isLightTheme(mSettings.theme)) {
    		setContentView(R.layout.loading_light);
    	} else {
    		setContentView(R.layout.loading_dark);
    	}
    	synchronized (COMMENT_ADAPTER_LOCK) {
	    	if (mCommentsAdapter != null)
	    		mCommentsAdapter.mIsLoading = true;
    	}
    	getWindow().setFeatureInt(Window.FEATURE_PROGRESS, 0);
    }
    
    /**
     * Refresh the body TextView of visible comments. Call from UI Thread.
     */
    private void refreshVisibleComments() {
		int firstPosition = getListView().getFirstVisiblePosition();
		int lastPosition = getListView().getLastVisiblePosition();
		for (int i = firstPosition; i <= lastPosition; i++)
			refreshCommentBodyTextView(i);
	}

    private void refreshCommentBodyTextView(int commentIndex) {
		View v = getListView().getChildAt(commentIndex);
		if (v != null) {
			View bodyTextView = v.findViewById(R.id.body);
			if (bodyTextView != null) {
				synchronized (COMMENT_ADAPTER_LOCK) {
						((TextView) bodyTextView).setText(mCommentsAdapter.getItem(commentIndex).getSpannedBody());
				}
			}
		}
    }

	private boolean isPositionVisible(int position) {
		return position <= getListView().getLastVisiblePosition() && position >= getListView().getFirstVisiblePosition();
	}
	
    
    
    private class MyLoginTask extends LoginTask {
    	public MyLoginTask(String username, String password) {
    		super(username, password, mSettings, mClient, getApplicationContext());
    	}
    	
    	@Override
    	protected void onPreExecute() {
    		showDialog(Constants.DIALOG_LOGGING_IN);
    	}
    	
    	@Override
    	protected void onPostExecute(Boolean success) {
    		dismissDialog(Constants.DIALOG_LOGGING_IN);
    		if (success) {
    			Toast.makeText(CommentsListActivity.this, "Logged in as "+mUsername, Toast.LENGTH_SHORT).show();
    			// Check mail
    			new PeekEnvelopeTask(CommentsListActivity.this, mClient, mSettings.mailNotificationStyle).execute();
	    		// Refresh the comments list
    			new DownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
    		} else {
            	Common.showErrorToast(mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
    		}
    	}
    }
    
    
    
    
    private class CommentReplyTask extends AsyncTask<String, Void, String> {
    	private String _mParentThingId;
    	String _mUserError = "Error submitting reply. Please try again.";
    	
    	CommentReplyTask(String parentThingId) {
    		_mParentThingId = parentThingId;
    	}
    	
    	@Override
        public String doInBackground(String... text) {
        	HttpEntity entity = null;
        	
        	if (!mSettings.isLoggedIn()) {
        		Common.showErrorToast("You must be logged in to reply.", Toast.LENGTH_LONG, CommentsListActivity.this);
        		_mUserError = "Not logged in";
        		return null;
        	}
        	// Update the modhash if necessary
        	if (mSettings.modhash == null) {
        		String modhash = Common.doUpdateModhash(mClient);
        		if (modhash == null) {
        			// doUpdateModhash should have given an error about credentials
        			Common.doLogout(mSettings, mClient, getApplicationContext());
        			if (Constants.LOGGING) Log.e(TAG, "Reply failed because doUpdateModhash() failed");
        			return null;
        		}
        		mSettings.setModhash(modhash);
        	}
        	
        	try {
        		// Construct data
    			List<NameValuePair> nvps = new ArrayList<NameValuePair>();
    			nvps.add(new BasicNameValuePair("thing_id", _mParentThingId));
    			nvps.add(new BasicNameValuePair("text", text[0]));
    			nvps.add(new BasicNameValuePair("r", mSubreddit));
    			nvps.add(new BasicNameValuePair("uh", mSettings.modhash));
    			// Votehash is currently unused by reddit 
//    				nvps.add(new BasicNameValuePair("vh", "0d4ab0ffd56ad0f66841c15609e9a45aeec6b015"));
    			
    			HttpPost httppost = new HttpPost("http://www.reddit.com/api/comment");
    	        httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
    	        
    	        HttpParams params = httppost.getParams();
    	        HttpConnectionParams.setConnectionTimeout(params, 40000);
    	        HttpConnectionParams.setSoTimeout(params, 40000);
    	        
    	        if (Constants.LOGGING) Log.d(TAG, nvps.toString());
    	        
                // Perform the HTTP POST request
    	    	HttpResponse response = mClient.execute(httppost);
    	    	entity = response.getEntity();
    	    	
            	// Getting here means success. Create a new CommentInfo.
            	return Common.checkIDResponse(response, entity);
            	
        	} catch (Exception e) {
        		if (Constants.LOGGING) Log.e(TAG, "CommentReplyTask", e);
        		_mUserError = e.getMessage();
        	} finally {
        		if (entity != null) {
        			try {
        				entity.consumeContent();
        			} catch (Exception e2) {
        				if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
        			}
        		}
        	}
        	return null;
        }
    	
    	@Override
    	public void onPreExecute() {
    		showDialog(Constants.DIALOG_REPLYING);
    	}
    	
    	@Override
    	public void onPostExecute(String newId) {
    		dismissDialog(Constants.DIALOG_REPLYING);
    		if (newId == null) {
    			Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
    		} else {
    			// Refresh
    			CacheInfo.invalidateCachedThread(getApplicationContext());
    			new DownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
    		}
    	}
    }
    
    private class EditTask extends AsyncTask<String, Void, String> {
    	private String _mThingId;
    	String _mUserError = "Error submitting edit. Please try again.";
    	
    	EditTask(String thingId) {
    		_mThingId = thingId;
    	}
    	
    	@Override
        public String doInBackground(String... text) {
        	HttpEntity entity = null;
        	
        	if (!mSettings.isLoggedIn()) {
        		_mUserError = "You must be logged in to edit.";
        		return null;
        	}
        	// Update the modhash if necessary
        	if (mSettings.modhash == null) {
        		String modhash = Common.doUpdateModhash(mClient);
        		if (modhash == null) {
        			// doUpdateModhash should have given an error about credentials
        			Common.doLogout(mSettings, mClient, getApplicationContext());
        			if (Constants.LOGGING) Log.e(TAG, "Reply failed because doUpdateModhash() failed");
        			return null;
        		}
        		mSettings.setModhash(modhash);
        	}
        	
        	try {
        		// Construct data
    			List<NameValuePair> nvps = new ArrayList<NameValuePair>();
    			nvps.add(new BasicNameValuePair("thing_id", _mThingId.toString()));
    			nvps.add(new BasicNameValuePair("text", text[0].toString()));
    			nvps.add(new BasicNameValuePair("r", mSubreddit.toString()));
    			nvps.add(new BasicNameValuePair("uh", mSettings.modhash.toString()));
    			
    			HttpPost httppost = new HttpPost("http://www.reddit.com/api/editusertext");
    	        httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
    	        
    	        HttpParams params = httppost.getParams();
    	        HttpConnectionParams.setConnectionTimeout(params, 40000);
    	        HttpConnectionParams.setSoTimeout(params, 40000);
    	        
    	        if (Constants.LOGGING) Log.d(TAG, nvps.toString());
    	        
                // Perform the HTTP POST request
    	    	HttpResponse response = mClient.execute(httppost);
    	    	entity = response.getEntity();
    	    	
    	    	return Common.checkIDResponse(response, entity);
            	
        	} catch (Exception e) {
        		if (Constants.LOGGING) Log.e(TAG, "EditTask", e);
        		_mUserError = e.getMessage();
        	} finally {
        		if (entity != null) {
        			try {
        				entity.consumeContent();
        			} catch (Exception e2) {
        				if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
        			}
        		}
        	}
        	return null;
        }
    	
    	@Override
    	public void onPreExecute() {
    		showDialog(Constants.DIALOG_EDITING);
    	}
    	
    	@Override
    	public void onPostExecute(String newId) {
    		dismissDialog(Constants.DIALOG_EDITING);
    		if (newId == null) {
    			Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
    		} else {
    			// Refresh
    			CacheInfo.invalidateCachedThread(getApplicationContext());
    			new DownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
    		}
    	}
    }
    
    private class DeleteTask extends AsyncTask<String, Void, Boolean> {
    	private String _mUserError = "Error deleting. Please try again.";
    	private String _mKind;
    	
    	public DeleteTask(String kind) {
    		_mKind = kind;
    	}
    	
    	@Override
    	public Boolean doInBackground(String... thingFullname) {
//    		POSTDATA=id=t1_c0cxa7l&executed=deleted&r=test&uh=f7jb1yjwfqd4ffed8356eb63fcfbeeadad142f57c56e9cbd9e
    		
    		HttpEntity entity = null;
        	
        	if (!mSettings.isLoggedIn()) {
        		_mUserError = "You must be logged in to delete.";
        		return false;
        	}
        	// Update the modhash if necessary
        	if (mSettings.modhash == null) {
        		String modhash = Common.doUpdateModhash(mClient);
        		if (modhash == null) {
        			// doUpdateModhash should have given an error about credentials
        			Common.doLogout(mSettings, mClient, getApplicationContext());
        			if (Constants.LOGGING) Log.e(TAG, "Reply failed because doUpdateModhash() failed");
        			return false;
        		}
        		mSettings.setModhash(modhash);
        	}
        	
        	try {
        		// Construct data
    			List<NameValuePair> nvps = new ArrayList<NameValuePair>();
    			nvps.add(new BasicNameValuePair("id", thingFullname[0].toString()));
    			nvps.add(new BasicNameValuePair("executed", "deleted"));
    			nvps.add(new BasicNameValuePair("r", mSubreddit.toString()));
    			nvps.add(new BasicNameValuePair("uh", mSettings.modhash.toString()));
    			
    			HttpPost httppost = new HttpPost("http://www.reddit.com/api/del");
    	        httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
    	        
    	        HttpParams params = httppost.getParams();
    	        HttpConnectionParams.setConnectionTimeout(params, 40000);
    	        HttpConnectionParams.setSoTimeout(params, 40000);
    	        
    	        if (Constants.LOGGING) Log.d(TAG, nvps.toString());
    	        
                // Perform the HTTP POST request
    	    	HttpResponse response = mClient.execute(httppost);
            	entity = response.getEntity();

            	String error = Common.checkResponseErrors(response, entity);
            	if (error != null)
            		throw new Exception(error);

            	// Success
            	return true;
            	
        	} catch (Exception e) {
        		if (Constants.LOGGING) Log.e(TAG, "DeleteTask", e);
        		_mUserError = e.getMessage();
        	} finally {
        		if (entity != null) {
        			try {
        				entity.consumeContent();
        			} catch (Exception e2) {
        				if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
        			}
        		}
        	}
        	return false;
    	}
    	
    	@Override
    	public void onPreExecute() {
    		showDialog(Constants.DIALOG_DELETING);
    	}
    	
    	@Override
    	public void onPostExecute(Boolean success) {
    		dismissDialog(Constants.DIALOG_DELETING);
    		if (success) {
    			CacheInfo.invalidateCachedThread(getApplicationContext());
    			if (Constants.THREAD_KIND.equals(_mKind)) {
    				Toast.makeText(CommentsListActivity.this, "Deleted thread.", Toast.LENGTH_LONG).show();
    				finish();
    				return;
    			} else {
    				Toast.makeText(CommentsListActivity.this, "Deleted comment.", Toast.LENGTH_SHORT).show();
    				new DownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
    			}
    		} else {
    			Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
    		}
    	}
    }

    private class VoteTask extends AsyncTask<Void, Void, Boolean> {
    	
    	private static final String TAG = "VoteWorker";
    	
    	private String _mThingFullname;
    	private int _mDirection;
    	private String _mUserError = "Error voting.";
    	private ThingInfo _mTargetThingInfo;
    	
    	// Save the previous arrow and score in case we need to revert
    	private int _mPreviousUps, _mPreviousDowns;
    	private Boolean _mPreviousLikes;
    	
    	VoteTask(String thingFullname, int direction) {
    		_mThingFullname = thingFullname;
    		_mDirection = direction;
    		// Copy these because they can change while voting thread is running
    		_mTargetThingInfo = mVoteTargetThing;
    	}
    	
    	@Override
    	public Boolean doInBackground(Void... v) {
        	HttpEntity entity = null;
        	
        	if (!mSettings.isLoggedIn()) {
        		_mUserError = "You must be logged in to vote.";
        		return false;
        	}
        	
        	// Update the modhash if necessary
        	if (mSettings.modhash == null) {
        		String modhash = Common.doUpdateModhash(mClient); 
        		if (modhash == null) {
        			// doUpdateModhash should have given an error about credentials
        			Common.doLogout(mSettings, mClient, getApplicationContext());
        			if (Constants.LOGGING) Log.e(TAG, "Vote failed because doUpdateModhash() failed");
        			return false;
        		}
        		mSettings.setModhash(modhash);
        	}
        	
        	try {
        		// Construct data
    			List<NameValuePair> nvps = new ArrayList<NameValuePair>();
    			nvps.add(new BasicNameValuePair("id", _mThingFullname.toString()));
    			nvps.add(new BasicNameValuePair("dir", String.valueOf(_mDirection)));
    			nvps.add(new BasicNameValuePair("r", mSubreddit.toString()));
    			nvps.add(new BasicNameValuePair("uh", mSettings.modhash.toString()));
    			// Votehash is currently unused by reddit 
//    				nvps.add(new BasicNameValuePair("vh", "0d4ab0ffd56ad0f66841c15609e9a45aeec6b015"));
    			
    			HttpPost httppost = new HttpPost("http://www.reddit.com/api/vote");
    	        httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
    	        
    	        if (Constants.LOGGING) Log.d(TAG, nvps.toString());
    	        
                // Perform the HTTP POST request
    	    	HttpResponse response = mClient.execute(httppost);
            	entity = response.getEntity();

            	String error = Common.checkResponseErrors(response, entity);
            	if (error != null)
            		throw new Exception(error);

            	return true;
        	} catch (Exception e) {
        		if (Constants.LOGGING) Log.e(TAG, "VoteTask", e);
        		_mUserError = e.getMessage();
        	} finally {
        		if (entity != null) {
        			try {
        				entity.consumeContent();
        			} catch (Exception e2) {
        				if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
        			}
        		}
        	}
        	return false;
        }
    	
    	public void onPreExecute() {
        	if (!mSettings.isLoggedIn()) {
        		Common.showErrorToast("You must be logged in to vote.", Toast.LENGTH_LONG, CommentsListActivity.this);
        		cancel(true);
        		return;
        	}
        	if (_mDirection < -1 || _mDirection > 1) {
        		if (Constants.LOGGING) Log.e(TAG, "WTF: _mDirection = " + _mDirection);
        		throw new RuntimeException("How the hell did you vote something besides -1, 0, or 1?");
        	}

    		int newUps, newDowns;
        	Boolean newLikes;
        	_mPreviousUps = Integer.valueOf(_mTargetThingInfo.getUps());
        	_mPreviousDowns = Integer.valueOf(_mTargetThingInfo.getDowns());
    	    newUps = _mPreviousUps;
    	    newDowns = _mPreviousDowns;
    	    _mPreviousLikes = _mTargetThingInfo.getLikes();
        	
    	    if (_mPreviousLikes == null) {
	    		if (_mDirection == 1) {
	    			newUps = _mPreviousUps + 1;
	    			newLikes = true;
	    		} else if (_mDirection == -1) {
	    			newDowns = _mPreviousDowns + 1;
	    			newLikes = false;
	    		} else {
	    			cancel(true);
	    			return;
	    		}
    	    } else if (_mPreviousLikes == true) {
    	    	if (_mDirection == 0) {
	    			newUps = _mPreviousUps - 1;
	    			newLikes = null;
	    		} else if (_mDirection == -1) {
	    			newUps = _mPreviousUps - 1;
	    			newDowns = _mPreviousDowns + 1;
	    			newLikes = false;
	    		} else {
	    			cancel(true);
	    			return;
	    		}
	    	} else {
	    		if (_mDirection == 1) {
	    			newUps = _mPreviousUps + 1;
	    			newDowns = _mPreviousDowns - 1;
	    			newLikes = true;
	    		} else if (_mDirection == 0) {
	    			newDowns = _mPreviousDowns - 1;
	    			newLikes = null;
	    		} else {
	    			cancel(true);
	    			return;
	    		}
	    	}

    		_mTargetThingInfo.setLikes(newLikes);
    		_mTargetThingInfo.setUps(newUps);
    		_mTargetThingInfo.setDowns(newDowns);
    		_mTargetThingInfo.setScore(newUps - newDowns);
        	mCommentsAdapter.notifyDataSetChanged();
    	}
    	
    	public void onPostExecute(Boolean success) {
    		if (success) {
    			CacheInfo.invalidateCachedThread(getApplicationContext());
    		} else {
    			// Vote failed. Undo the arrow and score.
            	_mTargetThingInfo.setLikes(_mPreviousLikes);
       			_mTargetThingInfo.setUps(_mPreviousUps);
       			_mTargetThingInfo.setDowns(_mPreviousDowns);
       			_mTargetThingInfo.setScore(_mPreviousUps - _mPreviousDowns);
        		mCommentsAdapter.notifyDataSetChanged();
        		
    			Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
    		}
    	}
    }
    
    

    private class ReportTask extends AsyncTask<Void, Void, Boolean> {
    	
    	private static final String TAG = "ReportTask";
    	
    	private String _mUserError = "Error reporting.";
    	private String _mFullId;
    	
    	ReportTask(String fullname) {
    		this._mFullId = fullname;
    	}
    	
    	@Override
    	public Boolean doInBackground(Void... v) {
        	HttpEntity entity = null;
        	
        	if (!mSettings.isLoggedIn()) {
        		_mUserError = "You must be logged in to report something.";
        		return false;
        	}
        	
        	// Update the modhash if necessary
        	if (mSettings.modhash == null) {
        		String modhash = Common.doUpdateModhash(mClient); 
        		if (modhash == null) {
        			// doUpdateModhash should have given an error about credentials
        			Common.doLogout(mSettings, mClient, getApplicationContext());
        			if (Constants.LOGGING) Log.e(TAG, "Report failed because doUpdateModhash() failed");
        			return false;
        		}
        		mSettings.setModhash(modhash);
        	}
        	
        	try {
        		// Construct data
    			List<NameValuePair> nvps = new ArrayList<NameValuePair>();
    			nvps.add(new BasicNameValuePair("id", _mFullId));
    			nvps.add(new BasicNameValuePair("executed", "reported"));
    			nvps.add(new BasicNameValuePair("r", mSubreddit.toString()));
    			nvps.add(new BasicNameValuePair("uh", mSettings.modhash.toString()));
    			// Votehash is currently unused by reddit 
//    				nvps.add(new BasicNameValuePair("vh", "0d4ab0ffd56ad0f66841c15609e9a45aeec6b015"));
    			
    			HttpPost httppost = new HttpPost("http://www.reddit.com/api/report");
    	        httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
    	        
    	        if (Constants.LOGGING) Log.d(TAG, nvps.toString());
    	        
                // Perform the HTTP POST request
    	    	HttpResponse response = mClient.execute(httppost);
    	    	entity = response.getEntity();

    	    	String error = Common.checkResponseErrors(response, entity);
            	if (error != null)
            		throw new Exception(error);

            	// Success
            	return true;

        	} catch (Exception e) {
        		if (Constants.LOGGING) Log.e(TAG, "ReportTask", e);
        	} finally {
        		if (entity != null) {
        			try {
        				entity.consumeContent();
        			} catch (Exception e2) {
        				if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
        			}
        		}
        	}
        	return false;
        }
    	
    	public void onPreExecute() {
	        if (!mSettings.isLoggedIn()) {
	        	Common.showErrorToast("You must be logged in to report this.", Toast.LENGTH_LONG, CommentsListActivity.this);
	        	cancel(true);
	        	return;
	        }
    	}
    	
    	public void onPostExecute(Boolean success) {
    		if (success) {
    			Toast.makeText(CommentsListActivity.this, "Reported.", Toast.LENGTH_SHORT);
    		} else {
    			Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
    		}
    	}
    }

    /**
     * Populates the menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.comments, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // This happens when the user begins to hold down the menu key, so
        // allow them to chord to get a shortcut.
        mCanChord = true;

    	super.onPrepareOptionsMenu(menu);
    	
    	MenuItem src, dest;

        menu.findItem(R.id.find_next_menu_id).setVisible(last_search_string != null && last_search_string.length() > 0);

        // Login/Logout
    	if (mSettings.isLoggedIn()) {
	        menu.findItem(R.id.login_logout_menu_id).setTitle(
	        		String.format(getResources().getString(R.string.logout), mSettings.username));
	        menu.findItem(R.id.inbox_menu_id).setVisible(true);
	        menu.findItem(R.id.user_profile_menu_id).setVisible(true);
	        menu.findItem(R.id.user_profile_menu_id).setTitle(
	        		String.format(getResources().getString(R.string.user_profile), mSettings.username));
    	} else {
            menu.findItem(R.id.login_logout_menu_id).setTitle(getResources().getString(R.string.login));
            menu.findItem(R.id.inbox_menu_id).setVisible(false);
	        menu.findItem(R.id.user_profile_menu_id).setVisible(false);
    	}
    	
    	// Edit and delete
    	if (mOpThingInfo != null) {
	    	if (mSettings.username != null && mSettings.username.equalsIgnoreCase(mOpThingInfo.getAuthor())) {
				if (mOpThingInfo.getSelftext_html() != null)
					menu.findItem(R.id.op_edit_menu_id).setVisible(true);
				else
					menu.findItem(R.id.op_edit_menu_id).setVisible(false);
				menu.findItem(R.id.op_delete_menu_id).setVisible(true);
			} else {
				menu.findItem(R.id.op_edit_menu_id).setVisible(false);
				menu.findItem(R.id.op_delete_menu_id).setVisible(false);
			}
    	}
    	
    	// Theme: Light/Dark
    	src = Util.isLightTheme(mSettings.theme) ?
        		menu.findItem(R.id.dark_menu_id) :
        			menu.findItem(R.id.light_menu_id);
        dest = menu.findItem(R.id.light_dark_menu_id);
        dest.setTitle(src.getTitle());
        
        // Sort
        if (Constants.CommentsSort.SORT_BY_BEST_URL.equals(mSettings.commentsSortByUrl))
        	src = menu.findItem(R.id.sort_by_best_menu_id);
        else if (Constants.CommentsSort.SORT_BY_HOT_URL.equals(mSettings.commentsSortByUrl))
        	src = menu.findItem(R.id.sort_by_hot_menu_id);
        else if (Constants.CommentsSort.SORT_BY_NEW_URL.equals(mSettings.commentsSortByUrl))
        	src = menu.findItem(R.id.sort_by_new_menu_id);
        else if (Constants.CommentsSort.SORT_BY_CONTROVERSIAL_URL.equals(mSettings.commentsSortByUrl))
        	src = menu.findItem(R.id.sort_by_controversial_menu_id);
        else if (Constants.CommentsSort.SORT_BY_TOP_URL.equals(mSettings.commentsSortByUrl))
        	src = menu.findItem(R.id.sort_by_top_menu_id);
        else if (Constants.CommentsSort.SORT_BY_OLD_URL.equals(mSettings.commentsSortByUrl))
        	src = menu.findItem(R.id.sort_by_old_menu_id);
        dest = menu.findItem(R.id.sort_by_menu_id);
        dest.setTitle(src.getTitle());
    	
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!mCanChord) {
            // The user has already fired a shortcut with this hold down of the
            // menu key.
            return false;
        }
        
        switch (item.getItemId()) {
        case R.id.op_menu_id:
        	if (mOpThingInfo == null)
        		break;
    		mVoteTargetThing = mOpThingInfo;
        	mReplyTargetName = mOpThingInfo.getName();
    		showDialog(Constants.DIALOG_COMMENT_CLICK);
    		break;
    	case R.id.op_subreddit_menu_id:
			Intent intent = new Intent(getApplicationContext(), ThreadsListActivity.class);
			intent.setData(Util.createSubredditUri(mSubreddit));
			intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			startActivity(intent);
			Util.overridePendingTransition(mActivity_overridePendingTransition, this,
					android.R.anim.slide_in_left, android.R.anim.slide_out_right);
			break;
    	case R.id.login_logout_menu_id:
        	if (mSettings.isLoggedIn()) {
        		Common.doLogout(mSettings, mClient, getApplicationContext());
        		Toast.makeText(this, "You have been logged out.", Toast.LENGTH_SHORT).show();
        		new DownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
        	} else {
        		showDialog(Constants.DIALOG_LOGIN);
        	}
            break;
        case R.id.find_next_menu_id:
            if (last_search_string != null && last_search_string.length() > 0)
                findCommentText(last_search_string, true, true);
            break;
        case R.id.find_base_id:
            // This case is needed because the "default" case throws
            // an error, otherwise precluding anonymous "parent" menu items
            break;
        case R.id.find_menu_id:
            showDialog(Constants.DIALOG_FIND);
            break;
    	case R.id.refresh_menu_id:
    		CacheInfo.invalidateCachedThread(getApplicationContext());
    		new DownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
    		break;
    	case R.id.sort_by_menu_id:
    		showDialog(Constants.DIALOG_SORT_BY);
    		break;
    	case R.id.open_browser_menu_id:
    		String url = new StringBuilder("http://www.reddit.com/r/")
				.append(mSubreddit).append("/comments/").append(mThreadId).toString();
    		Common.launchBrowser(this, url, url, false, true, true);
    		break;
    	case R.id.op_delete_menu_id:
    		mReplyTargetName = mOpThingInfo.getName();
    		mDeleteTargetKind = Constants.THREAD_KIND;
    		showDialog(Constants.DIALOG_DELETE);
    		break;
    	case R.id.op_edit_menu_id:
    		mReplyTargetName = mOpThingInfo.getName();
    		mEditTargetBody = mOpThingInfo.getSelftext();
    		showDialog(Constants.DIALOG_EDIT);
    		break;
    	case R.id.light_dark_menu_id:
    		mSettings.setTheme(Util.getInvertedTheme(mSettings.theme));
    		resetUI(mCommentsAdapter);
    		if (mCommentsAdapter != null) {
    			markSubmitterComments();
    			mCommentsAdapter.notifyDataSetChanged();
    		}
    		break;
        case R.id.inbox_menu_id:
        	Intent inboxIntent = new Intent(getApplicationContext(), InboxActivity.class);
        	startActivity(inboxIntent);
        	break;
        case R.id.user_profile_menu_id:
        	Intent profileIntent = new Intent(getApplicationContext(), ProfileActivity.class);
        	startActivity(profileIntent);
        	break;
    	case R.id.preferences_menu_id:
            Intent prefsIntent = new Intent(getApplicationContext(), RedditPreferencesPage.class);
            startActivity(prefsIntent);
            break;

    	default:
    		throw new IllegalArgumentException("Unexpected action value "+item.getItemId());
    	}
    	
        return true;
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
    	super.onCreateContextMenu(menu, v, menuInfo);
    	AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
    	int rowId = (int) info.id;
    	
    	ThingInfo item = mCommentsAdapter.getItem(rowId);
    	
    	if (rowId == 0) {
    		menu.add(0, Constants.SHARE_CONTEXT_ITEM, Menu.NONE, "Share");

    		if(mOpThingInfo.isSaved()){
    			menu.add(0, Constants.UNSAVE_CONTEXT_ITEM, Menu.NONE, "Unsave");
    		} else {
    			menu.add(0, Constants.SAVE_CONTEXT_ITEM, Menu.NONE, "Save");
    		}
    		if(mOpThingInfo.isHidden()){
    			menu.add(0, Constants.UNHIDE_CONTEXT_ITEM, Menu.NONE, "Unhide");
    		} else {
    			menu.add(0, Constants.HIDE_CONTEXT_ITEM, Menu.NONE, "Hide");
    		}
    		
    		menu.add(0, Constants.DIALOG_VIEW_PROFILE, Menu.NONE,
    				String.format(getResources().getString(R.string.user_profile), item.getAuthor()));
    		
    	} else if (isLoadMoreCommentsPosition(rowId)) {
    		menu.add(0, Constants.DIALOG_GOTO_PARENT, Menu.NONE, "Go to parent");
    	} else if (isHiddenCommentHeadPosition(rowId)) {
    		menu.add(0, Constants.DIALOG_SHOW_COMMENT, Menu.NONE, "Show comment");
    		menu.add(0, Constants.DIALOG_GOTO_PARENT, Menu.NONE, "Go to parent");
    	} else {
    		synchronized (COMMENT_ADAPTER_LOCK) {
	    		if (mSettings.username != null && mSettings.username.equalsIgnoreCase(item.getAuthor())) {
	    			menu.add(0, Constants.DIALOG_EDIT, Menu.NONE, "Edit");
	    			menu.add(0, Constants.DIALOG_DELETE, Menu.NONE, "Delete");
	    		}
    		}
    		menu.add(0, Constants.DIALOG_HIDE_COMMENT, Menu.NONE, "Hide comment");
//    		if (mSettings.isLoggedIn())
//    			menu.add(0, Constants.DIALOG_REPORT, Menu.NONE, "Report comment");
    		menu.add(0, Constants.DIALOG_GOTO_PARENT, Menu.NONE, "Go to parent");
    		menu.add(0, Constants.DIALOG_VIEW_PROFILE, Menu.NONE,
    				String.format(getResources().getString(R.string.user_profile), item.getAuthor()));
    	}
    }
    
    @Override
    public boolean onContextItemSelected(MenuItem item) {
    	AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
    	int rowId = (int) info.id;
    	
    	switch (item.getItemId()) {
    	case Constants.SAVE_CONTEXT_ITEM:
    		new SaveTask(true, mOpThingInfo, mSettings, this).execute();
    		return true;
    		
    	case Constants.UNSAVE_CONTEXT_ITEM:
    		new SaveTask(false, mOpThingInfo, mSettings, this).execute();
    		return true;
    		
    	case Constants.HIDE_CONTEXT_ITEM:
    		new HideTask(true, mOpThingInfo, mSettings, this).execute();
    		return true;
    		
    	case Constants.UNHIDE_CONTEXT_ITEM:
    		new HideTask(false, mOpThingInfo, mSettings, this).execute();
    		return true;
    		
    	case Constants.SHARE_CONTEXT_ITEM:
    		Intent intent = new Intent();
			intent.setAction(Intent.ACTION_SEND);
			intent.setType("text/plain");

			intent.putExtra(Intent.EXTRA_TEXT, mOpThingInfo.getUrl());
			
			try {
				startActivity(Intent.createChooser(intent, "Share Link"));
			} catch (android.content.ActivityNotFoundException ex) {
				
			}
			
			return true;
			
    	case Constants.DIALOG_HIDE_COMMENT:
    		hideComment(rowId);
    		return true;
    		
    	case Constants.DIALOG_SHOW_COMMENT:
    		showComment(rowId);
    		return true;
    		
    	case Constants.DIALOG_GOTO_PARENT:
    		synchronized (COMMENT_ADAPTER_LOCK) {
    			int myIndent = mCommentsAdapter.getItem(rowId).getIndent();
	    		int parentRowId;
	    		for (parentRowId = rowId - 1; parentRowId >= 0; parentRowId--)
	    			if (mCommentsAdapter.getItem(parentRowId).getIndent() < myIndent)
	    				break;
	    		getListView().setSelection(parentRowId);
    		}
    		return true;
    		
    	case Constants.DIALOG_VIEW_PROFILE:
    		Intent i = new Intent(this, ProfileActivity.class);
    		i.setData(Util.createProfileUri(mCommentsAdapter.getItem(rowId).getAuthor()));
    		startActivity(i);
    		return true;
    		
    	case Constants.DIALOG_EDIT:
    		synchronized (COMMENT_ADAPTER_LOCK) {
	    		mReplyTargetName = mCommentsAdapter.getItem(rowId).getName();
	    		mEditTargetBody = mCommentsAdapter.getItem(rowId).getBody();
    		}
    		showDialog(Constants.DIALOG_EDIT);
    		return true;
    		
    	case Constants.DIALOG_DELETE:
    		synchronized (COMMENT_ADAPTER_LOCK) {
    			mReplyTargetName = mCommentsAdapter.getItem(rowId).getName();
    		}
    		// It must be a comment, since the OP selftext is reached via options menu, not context menu
    		mDeleteTargetKind = Constants.COMMENT_KIND;
    		showDialog(Constants.DIALOG_DELETE);
    		return true;
    		
    	case Constants.DIALOG_REPORT:
    		synchronized (COMMENT_ADAPTER_LOCK) {
    			mReportTargetName = mCommentsAdapter.getItem(rowId).getName();
    		}
    		showDialog(Constants.DIALOG_REPORT);
    		return true;
    		
		default:
    		return super.onContextItemSelected(item);	
    	}
    }
    
    private void hideComment(int rowId) {
    	synchronized (COMMENT_ADAPTER_LOCK) {
    		ThingInfo headComment = mCommentsAdapter.getItem(rowId);
	    	int myIndent = headComment.getIndent();
	    	headComment.setHiddenCommentHead(true);
	    	
	    	// Hide everything after the row.
	    	for (int i = rowId + 1; i < mCommentsAdapter.getCount(); i++) {
	    		ThingInfo ci = mCommentsAdapter.getItem(i);
	    		if (ci.getIndent() <= myIndent)
	    			break;
	    		ci.setHiddenCommentDescendant(true);
	    	}
	    	mCommentsAdapter.notifyDataSetChanged();
    	}
    	getListView().setSelection(rowId);
    }
    
    private void showComment(int rowId) {
    	synchronized (COMMENT_ADAPTER_LOCK) {
    		ThingInfo headComment = mCommentsAdapter.getItem(rowId);
    		headComment.setHiddenCommentHead(false);
	    	int stopIndent = headComment.getIndent();
	    	int skipIndentAbove = -1;
	    	for (int i = rowId + 1; i < mCommentsAdapter.getCount(); i++) {
	    		ThingInfo ci = mCommentsAdapter.getItem(i);
	    		int ciIndent = ci.getIndent();
	    		if (ciIndent <= stopIndent)
	    			break;
	    		if (skipIndentAbove != -1 && ciIndent > skipIndentAbove)
	    			continue;

	    		ci.setHiddenCommentDescendant(false);
	    		
	    		// skip nested hidden comments (e.g. you collapsed child first, then root. now expanding root, but don't expand child) 
	    		if (ci.isHiddenCommentHead())
	    			skipIndentAbove = ci.getIndent();
	    		else
		    		skipIndentAbove = -1;
	    	}
	    	mCommentsAdapter.notifyDataSetChanged();
    	}
    	getListView().setSelection(rowId);
    }

	private void findCommentText(String search_text, boolean wrap, boolean next) {
		last_search_string = search_text;
		int current_position = next
			? (last_found_position + 1) % mCommentsAdapter.getCount()
			: Math.max(0, getSelectedItemPosition());

		if ( getFoundPosition(current_position, mCommentsAdapter.getCount(), search_text) ) {
			mCommentsAdapter.notifyDataSetChanged();
			return;
		}

		if ( wrap ) {
			Log.d(TAG, "Continuing search from top...");
			if ( getFoundPosition(0, current_position, search_text) ) {
				mCommentsAdapter.notifyDataSetChanged();
				return;
			}
		}

		mCommentsAdapter.notifyDataSetChanged();

		String not_found_msg = getResources().getString(R.string.find_not_found, search_text);
    	Toast.makeText(CommentsListActivity.this, not_found_msg, Toast.LENGTH_LONG).show();
	}

	private boolean getFoundPosition(int start_index, int end_index, String search_text) {
    	for (int i = start_index; i < end_index; i++) {
    		ThingInfo ci = mCommentsAdapter.getItem(i);

    		if (ci == null) continue;

    		String comment_body = ci.getBody();
    		if (comment_body == null) continue;

    		if (comment_body.toLowerCase().contains(search_text)) {
    			final int position = i;
    			getListView().post(new Runnable() {
	    			@Override
	    			public void run() {
	    				setSelection(position);
	    				getListView().requestFocus();
	    			}
    			});

				last_found_position = i;
				return true;
    		}
    	}
		last_found_position = -1;
    	return false;
	}

    @Override
    protected Dialog onCreateDialog(int id) {
    	Dialog dialog;
    	ProgressDialog pdialog;
    	AlertDialog.Builder builder;
    	LayoutInflater inflater;
    	
    	switch (id) {
    	case Constants.DIALOG_LOGIN:
    		dialog = new LoginDialog(this, mSettings, false) {
				@Override
				public void onLoginChosen(String user, String password) {
					dismissDialog(Constants.DIALOG_LOGIN);
    				new MyLoginTask(user, password).execute();
				}
			};
    		break;
    		
    	case Constants.DIALOG_COMMENT_CLICK:
    		inflater = (LayoutInflater)this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    		builder = new AlertDialog.Builder(this);
    		dialog = builder.setView(inflater.inflate(R.layout.comment_click_dialog, null)).create();
    		break;

    	case Constants.DIALOG_REPLY:
    	{
    		dialog = new Dialog(this);
    		dialog.setContentView(R.layout.compose_reply_dialog);
    		final EditText replyBody = (EditText) dialog.findViewById(R.id.body);
    		final Button replySaveButton = (Button) dialog.findViewById(R.id.reply_save_button);
    		final Button replyCancelButton = (Button) dialog.findViewById(R.id.reply_cancel_button);
			replySaveButton.setOnClickListener(new OnClickListener() {
    			public void onClick(View v) {
    				if (mReplyTargetName != null) {
	    				new CommentReplyTask(mReplyTargetName).execute(replyBody.getText().toString());
	    				dismissDialog(Constants.DIALOG_REPLY);
    				}
    				else {
    					Common.showErrorToast("Error replying. Please try again.", Toast.LENGTH_SHORT, CommentsListActivity.this);
    				}
    			}
    		});
    		replyCancelButton.setOnClickListener(replyCancelOnClickListener);
    	}
    		break;
    		
    	case Constants.DIALOG_EDIT:
    	{
    		dialog = new Dialog(this);
    		dialog.setContentView(R.layout.compose_reply_dialog);
    		final EditText replyBody = (EditText) dialog.findViewById(R.id.body);
    		final Button replySaveButton = (Button) dialog.findViewById(R.id.reply_save_button);
    		final Button replyCancelButton = (Button) dialog.findViewById(R.id.reply_cancel_button);
		
			replyBody.setText(mEditTargetBody);
			replySaveButton.setOnClickListener(new OnClickListener() {
    			public void onClick(View v) {
    				if (mReplyTargetName != null) {
	    				new EditTask(mReplyTargetName).execute(replyBody.getText().toString());
	    				dismissDialog(Constants.DIALOG_EDIT);
    				}
    				else {
    					Common.showErrorToast("Error editing. Please try again.", Toast.LENGTH_SHORT, CommentsListActivity.this);
    				}
    			}
    		});
			replyCancelButton.setOnClickListener(new OnClickListener() {
    			public void onClick(View v) {
    				dismissDialog(Constants.DIALOG_EDIT);
    			}
    		});
		}
    		break;
    		
    	case Constants.DIALOG_DELETE:
    		builder = new AlertDialog.Builder(this);
    		builder.setTitle("Really delete this?");
    		builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
    			public void onClick(DialogInterface dialog, int item) {
    				dismissDialog(Constants.DIALOG_DELETE);
    				new DeleteTask(mDeleteTargetKind).execute(mReplyTargetName);
    			}
    		})
    		.setNegativeButton("No", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.cancel();
				}
			});
    		dialog = builder.create();
    		break;
    		
    	case Constants.DIALOG_SORT_BY:
    		builder = new AlertDialog.Builder(this);
    		builder.setTitle("Sort by:");
			int selectedSortBy = -1;
			for (int i = 0; i < Constants.CommentsSort.SORT_BY_URL_CHOICES.length; i++) {
				if (Constants.CommentsSort.SORT_BY_URL_CHOICES[i].equals(mSettings.commentsSortByUrl)) {
					selectedSortBy = i;
					break;
				}
			}
    		builder.setSingleChoiceItems(Constants.CommentsSort.SORT_BY_CHOICES, selectedSortBy, sortByOnClickListener);
    		dialog = builder.create();
    		break;
    		
    	case Constants.DIALOG_REPORT:
    		builder = new AlertDialog.Builder(this);
    		builder.setTitle("Really report this?");
    		builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
    			public void onClick(DialogInterface dialog, int item) {
    				dismissDialog(Constants.DIALOG_REPORT);
    				new ReportTask(mReportTargetName.toString()).execute();
    			}
    		})
    		.setNegativeButton("No", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.cancel();
				}
			});
    		dialog = builder.create();
    		break;
    		
   		// "Please wait"
    	case Constants.DIALOG_DELETING:
    		pdialog = new ProgressDialog(this);
    		pdialog.setMessage("Deleting...");
    		pdialog.setIndeterminate(true);
    		pdialog.setCancelable(false);
    		dialog = pdialog;
    		break;
    	case Constants.DIALOG_EDITING:
    		pdialog = new ProgressDialog(this);
    		pdialog.setMessage("Submitting edit...");
    		pdialog.setIndeterminate(true);
    		pdialog.setCancelable(false);
    		dialog = pdialog;
    		break;
    	case Constants.DIALOG_LOGGING_IN:
    		pdialog = new ProgressDialog(this);
    		pdialog.setMessage("Logging in...");
    		pdialog.setIndeterminate(true);
    		pdialog.setCancelable(false);
    		dialog = pdialog;
    		break;
    	case Constants.DIALOG_REPLYING:
    		pdialog = new ProgressDialog(this);
    		pdialog.setMessage("Sending reply...");
    		pdialog.setIndeterminate(true);
    		pdialog.setCancelable(false);
    		dialog = pdialog;
    		break;
    	case Constants.DIALOG_FIND:
    		inflater = (LayoutInflater)this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

    		View content = inflater.inflate(R.layout.dialog_find, null);
    		final EditText find_box = (EditText) content.findViewById(R.id.input_find_box);
//    		final CheckBox wrap_box = (CheckBox) content.findViewById(R.id.find_wrap_checkbox);

    		builder = new AlertDialog.Builder(this);
    		builder.setView(content);
    		builder.setTitle(R.string.find)
    		.setPositiveButton(R.string.find, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					String search_text = find_box.getText().toString().toLowerCase();
//					findCommentText(search_text, wrap_box.isChecked(), false);
					findCommentText(search_text, true, false);
				}
    		})
    		.setNegativeButton("Cancel", null);
    		dialog = builder.create();
    		break;
    	default:
    		throw new IllegalArgumentException("Unexpected dialog id "+id);
    	}
    	return dialog;
    }
    
    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
    	super.onPrepareDialog(id, dialog);
    	StringBuilder sb;
    	    	
    	switch (id) {
    	case Constants.DIALOG_LOGIN:
    		if (mSettings.username != null) {
	    		final TextView loginUsernameInput = (TextView) dialog.findViewById(R.id.login_username_input);
	    		loginUsernameInput.setText(mSettings.username);
    		}
    		final TextView loginPasswordInput = (TextView) dialog.findViewById(R.id.login_password_input);
    		loginPasswordInput.setText("");
    		break;
    		
    	case Constants.DIALOG_COMMENT_CLICK:
    		if (mVoteTargetThing == null)
    			break;
    		Boolean likes;
    		final TextView titleView = (TextView) dialog.findViewById(R.id.title);
    		final TextView urlView = (TextView) dialog.findViewById(R.id.url);
    		final TextView submissionStuffView = (TextView) dialog.findViewById(R.id.submissionTime_submitter_subreddit);
    		final Button linkButton = (Button) dialog.findViewById(R.id.thread_link_button);
			
    		if (mVoteTargetThing == mOpThingInfo) {
				likes = mVoteTargetThing.getLikes();
    			titleView.setVisibility(View.VISIBLE);
    			titleView.setText(mOpThingInfo.getTitle());
    			urlView.setVisibility(View.VISIBLE);
    			urlView.setText(mOpThingInfo.getUrl());
    			submissionStuffView.setVisibility(View.VISIBLE);
        		sb = new StringBuilder(Util.getTimeAgo(mOpThingInfo.getCreated_utc()))
	    			.append(" by ").append(mOpThingInfo.getAuthor());
        		submissionStuffView.setText(sb);
    			// For self posts, you're already there!
    			if (mOpThingInfo.getDomain().toLowerCase().startsWith("self.")) {
    				linkButton.setText(R.string.comment_links_button);
    				linkToEmbeddedURLs(linkButton);
    			} else {
    				final String url = mOpThingInfo.getUrl();
    				linkButton.setText(R.string.thread_link_button);
	    			linkButton.setOnClickListener(new OnClickListener() {
	    				public void onClick(View v) {
	    					dismissDialog(Constants.DIALOG_COMMENT_CLICK);
	    					// Launch Intent to goto the URL
	    					Common.launchBrowser(CommentsListActivity.this, url,
	    							Util.createThreadUri(mOpThingInfo).toString(),
	    							false, false, mSettings.useExternalBrowser);
	    				}
	    			});
	    			linkButton.setEnabled(true);
    			}
    		} else {
    			titleView.setText("Comment by " + mVoteTargetThing.getAuthor());
    			likes = mVoteTargetThing.getLikes();
    			urlView.setVisibility(View.INVISIBLE);
    			submissionStuffView.setVisibility(View.INVISIBLE);

    			// Get embedded URLs
    			linkButton.setText(R.string.comment_links_button);
    	    	linkToEmbeddedURLs(linkButton);
    		}
    		final CheckBox voteUpButton = (CheckBox) dialog.findViewById(R.id.vote_up_button);
    		final CheckBox voteDownButton = (CheckBox) dialog.findViewById(R.id.vote_down_button);
    		final Button replyButton = (Button) dialog.findViewById(R.id.reply_button);
    		final Button loginButton = (Button) dialog.findViewById(R.id.login_button);
    		
    		// Only show upvote/downvote if user is logged in
    		if (mSettings.isLoggedIn()) {
    			loginButton.setVisibility(View.GONE);
    			voteUpButton.setVisibility(View.VISIBLE);
    			voteDownButton.setVisibility(View.VISIBLE);
    			replyButton.setEnabled(true);
    			
    			// Make sure the setChecked() actions don't actually vote just yet.
    			voteUpButton.setOnCheckedChangeListener(null);
    			voteDownButton.setOnCheckedChangeListener(null);
    			
    			// Set initial states of the vote buttons based on user's past actions
	    		if (likes == null) {
	    			// User is currently neutral
	    			voteUpButton.setChecked(false);
	    			voteDownButton.setChecked(false);
	    		} else if (likes == true) {
	    			// User currenty likes it
	    			voteUpButton.setChecked(true);
	    			voteDownButton.setChecked(false);
	    		} else {
	    			// User currently dislikes it
	    			voteUpButton.setChecked(false);
	    			voteDownButton.setChecked(true);
	    		}
	    		// Now we want the user to be able to vote.
	    		voteUpButton.setOnCheckedChangeListener(voteUpOnCheckedChangeListener);
	    		voteDownButton.setOnCheckedChangeListener(voteDownOnCheckedChangeListener);

	    		// The "reply" button
    			replyButton.setOnClickListener(replyOnClickListener);	
	    	} else {
	    		replyButton.setEnabled(false);
    			
	    		voteUpButton.setVisibility(View.GONE);
    			voteDownButton.setVisibility(View.GONE);
    			loginButton.setVisibility(View.VISIBLE);
    			loginButton.setOnClickListener(loginOnClickListener);
    		}
    		break;
    		
    	case Constants.DIALOG_REPLY:
    		if (mVoteTargetThing != null && mVoteTargetThing.getReplyDraft() != null) {
    			EditText replyBodyView = (EditText) dialog.findViewById(R.id.body);
    			replyBodyView.setText(mVoteTargetThing.getReplyDraft());
    		} else if (mVoteTargetThing != null && mShouldClearReply) {
    			EditText replyBodyView = (EditText) dialog.findViewById(R.id.body);
    			replyBodyView.setText("");
    			mShouldClearReply = false;
    		}
    		break;
    		
		default:
			// No preparation based on app state is required.
			break;
    	}
    }
    
    /**
     * Helper function to add links from mVoteTargetThing to the button
     * @param linkButton Button that should open list of links
     */
    private void linkToEmbeddedURLs(Button linkButton) {
		final ArrayList<String> urls = new ArrayList<String>();
		final ArrayList<String> anchorTexts = new ArrayList<String>();
		final ArrayList<MarkdownURL> vtUrls = mVoteTargetThing.getUrls();
		int urlsCount = vtUrls.size();
		for (int i = 0; i < urlsCount; i++) {
			urls.add(vtUrls.get(i).url);
			anchorTexts.add(vtUrls.get(i).anchorText);
		}
		if (urlsCount == 0) {
			linkButton.setEnabled(false);
        } else {
        	linkButton.setEnabled(true);
        	linkButton.setOnClickListener(new OnClickListener() {
        		public void onClick(View v) {
        			dismissDialog(Constants.DIALOG_COMMENT_CLICK);      
        			
    	            ArrayAdapter<MarkdownURL> adapter = 
    	                new ArrayAdapter<MarkdownURL>(CommentsListActivity.this, android.R.layout.select_dialog_item, vtUrls) {
    	                public View getView(int position, View convertView, ViewGroup parent) {
    	                    View v = super.getView(position, convertView, parent);
    	                    try {
    	                        String url = getItem(position).url;
    	                        String anchorText = getItem(position).anchorText;
    	                        TextView tv = (TextView) v;
    	                        Drawable d = getPackageManager().getActivityIcon(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    	                        if (d != null) {
    	                            d.setBounds(0, 0, d.getIntrinsicHeight(), d.getIntrinsicHeight());
    	                            tv.setCompoundDrawablePadding(10);
    	                            tv.setCompoundDrawables(d, null, null, null);
    	                        }
    	                        final String telPrefix = "tel:";
    	                        if (url.startsWith(telPrefix)) {
    	                            url = PhoneNumberUtils.formatNumber(url.substring(telPrefix.length()));
    	                        }
								if (anchorText != null)
									tv.setText(Html.fromHtml(anchorText + "<br /><small>" + url + "</small>"));
								else
									tv.setText(Html.fromHtml(url));
    	                    } catch (android.content.pm.PackageManager.NameNotFoundException ex) {
    	                        ;
    	                    }
    	                    return v;
    	                }
    	            };

    	            AlertDialog.Builder b = new AlertDialog.Builder(CommentsListActivity.this);

    	            DialogInterface.OnClickListener click = new DialogInterface.OnClickListener() {
    	                public final void onClick(DialogInterface dialog, int which) {
    	                    if (which >= 0) {
    	                        Common.launchBrowser(CommentsListActivity.this, urls.get(which),
    	                        		Util.createThreadUri(mOpThingInfo).toString(),
    	                        		false, false, mSettings.useExternalBrowser);
    	                    }
    	                }
    	            };
    	                
    	            b.setTitle(R.string.select_link_title);
    	            b.setCancelable(true);
    	            b.setAdapter(adapter, click);

    	            b.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
    	                public final void onClick(DialogInterface dialog, int which) {
    	                    dialog.dismiss();
    	                }
    	            });

    	            b.show();
        		}
        	});
        }
    }
    
    static void fillCommentsListItemView(View view, ThingInfo item,
    		RedditSettings settings) {
        // Set the values of the Views for the CommentsListItem
        
        TextView votesView = (TextView) view.findViewById(R.id.votes);
        TextView submitterView = (TextView) view.findViewById(R.id.submitter);
        TextView bodyView = (TextView) view.findViewById(R.id.body);
        
        TextView submissionTimeView = (TextView) view.findViewById(R.id.submissionTime);
        ImageView voteUpView = (ImageView) view.findViewById(R.id.vote_up_image);
        ImageView voteDownView = (ImageView) view.findViewById(R.id.vote_down_image);
        
        try {
        	votesView.setText(Util.showNumPoints(item.getUps() - item.getDowns()));
        } catch (NumberFormatException e) {
        	// This happens because "ups" comes after the potentially long "replies" object,
        	// so the ListView might try to display the View before "ups" in JSON has been parsed.
        	if (Constants.LOGGING) Log.e(TAG, "getView, normal comment", e);
        }
        if (item.getSSAuthor() != null)
        	submitterView.setText(item.getSSAuthor());
        else
        	submitterView.setText(item.getAuthor());
        submissionTimeView.setText(Util.getTimeAgo(item.getCreated_utc()));
        
    	bodyView.setText(item.getSpannedBody());
        
        setCommentIndent(view, item.getIndent(), settings);
        
        if ("[deleted]".equals(item.getAuthor())) {
        	voteUpView.setVisibility(View.INVISIBLE);
        	voteDownView.setVisibility(View.INVISIBLE);
        }
        // Set the up and down arrow colors based on whether user likes
        else if (settings.isLoggedIn()) {
        	voteUpView.setVisibility(View.VISIBLE);
        	voteDownView.setVisibility(View.VISIBLE);
        	if (item.getLikes() == null) {
        		voteUpView.setImageResource(R.drawable.vote_up_gray);
        		voteDownView.setImageResource(R.drawable.vote_down_gray);
//        		votesView.setTextColor(res.getColor(R.color.gray));
        	} else if (item.getLikes() == true) {
        		voteUpView.setImageResource(R.drawable.vote_up_red);
        		voteDownView.setImageResource(R.drawable.vote_down_gray);
//        		votesView.setTextColor(res.getColor(R.color.arrow_red));
        	} else {
        		voteUpView.setImageResource(R.drawable.vote_up_gray);
        		voteDownView.setImageResource(R.drawable.vote_down_blue);
//        		votesView.setTextColor(res.getColor(R.color.arrow_blue));
        	}
        } else {
        	voteUpView.setVisibility(View.VISIBLE);
        	voteDownView.setVisibility(View.VISIBLE);
        	voteUpView.setImageResource(R.drawable.vote_up_gray);
    		voteDownView.setImageResource(R.drawable.vote_down_gray);
//    		votesView.setTextColor(res.getColor(R.color.gray));
        }

    }

    
    private final CompoundButton.OnCheckedChangeListener voteUpOnCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
    	public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
	    	dismissDialog(Constants.DIALOG_COMMENT_CLICK);
	    	String thingFullname = mVoteTargetThing.getName();
			if (isChecked)
				new VoteTask(thingFullname, 1).execute();
			else
				new VoteTask(thingFullname, 0).execute();
		}
    };
    private final CompoundButton.OnCheckedChangeListener voteDownOnCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
	    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
	    	dismissDialog(Constants.DIALOG_COMMENT_CLICK);
	    	String thingFullname = mVoteTargetThing.getName();
			if (isChecked)
				new VoteTask(thingFullname, -1).execute();
			else
				new VoteTask(thingFullname, 0).execute();
		}
    };
    
    private final OnClickListener replyOnClickListener = new OnClickListener() {
		public void onClick(View v) {
			dismissDialog(Constants.DIALOG_COMMENT_CLICK);
			showDialog(Constants.DIALOG_REPLY);
		}
	};
    private final OnClickListener replyCancelOnClickListener = new OnClickListener() {
		public void onClick(View v) {
			dismissDialog(Constants.DIALOG_REPLY);
		}
	};
	
	private final OnClickListener loginOnClickListener = new OnClickListener() {
		public void onClick(View v) {
			dismissDialog(Constants.DIALOG_COMMENT_CLICK);
			showDialog(Constants.DIALOG_LOGIN);
		}
	};
    
	private final DialogInterface.OnClickListener sortByOnClickListener = new DialogInterface.OnClickListener() {
		public void onClick(DialogInterface dialog, int item) {
			dialog.dismiss();
			mSettings.setCommentsSortByUrl(Constants.CommentsSort.SORT_BY_URL_CHOICES[item]);
			new DownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
		}
	};
	
	private final ThumbnailOnClickListenerFactory thumbnailOnClickListenerFactory
			= new ThumbnailOnClickListenerFactory() {
		public OnClickListener getThumbnailOnClickListener(String jumpToId, String url, String threadUrl, Context context) {
			return null;
		}
	};


    
    @Override
    protected void onSaveInstanceState(Bundle state) {
    	super.onSaveInstanceState(state);
    	state.putString(Constants.REPLY_TARGET_NAME_KEY, mReplyTargetName);
    	state.putString(Constants.REPORT_TARGET_NAME_KEY, mReportTargetName);
    	state.putString(Constants.EDIT_TARGET_BODY_KEY, mEditTargetBody);
    	state.putString(Constants.DELETE_TARGET_KIND_KEY, mDeleteTargetKind);
    	state.putString(Constants.SUBREDDIT_KEY, mSubreddit);
    	state.putString(Constants.THREAD_ID_KEY, mThreadId);
    	state.putString(Constants.THREAD_TITLE_KEY, mThreadTitle);
    }
    
    /**
     * Called to "thaw" re-animate the app from a previous onSaveInstanceState().
     * 
     * @see android.app.Activity#onRestoreInstanceState
     */
    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        final int[] myDialogs = {
    		Constants.DIALOG_COMMENT_CLICK,
        	Constants.DIALOG_DELETE,
        	Constants.DIALOG_DELETING,
        	Constants.DIALOG_EDIT,
        	Constants.DIALOG_EDITING,
        	Constants.DIALOG_LOGGING_IN,
        	Constants.DIALOG_LOGIN,
        	Constants.DIALOG_REPLY,
        	Constants.DIALOG_REPLYING,
        	Constants.DIALOG_SORT_BY,
        	Constants.DIALOG_REPORT
        };
        for (int dialog : myDialogs) {
	        try {
	        	dismissDialog(dialog);
		    } catch (IllegalArgumentException e) {
		    	// Ignore.
		    }
        }
    }
}
