/*
 * Copyright (C) 2013 Sergej Shafarenka, halfbit.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file kt in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package silent.kuasapmaterial.libs;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AbsListView;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SectionIndexer;

/**
 * ListView, which is capable to pin section views at its top while the rest is still scrolled.
 */
public class PinnedSectionListView extends ListView {

	static boolean mIsShadow = false;

	//-- inner classes
	// fields used for handling touch events
	private final Rect mTouchRect = new Rect();
	private final PointF mTouchPoint = new PointF();

	//-- class fields
	OnBottomReachedListener mBottomReachedListener;
	/**
	 * Delegating listener, can be null.
	 */
	OnScrollListener mDelegateOnScrollListener;
	/**
	 * Shadow for being recycled, can be null.
	 */
	PinnedSection mRecycleSection;
	/**
	 * shadow instance with a pinned view, can be null.
	 */
	PinnedSection mPinnedSection;
	/**
	 * Pinned view Y-translation. We use it to stick pinned view to the next section.
	 */
	int mTranslateY;
	private int mTouchSlop;
	private View mTouchTarget;
	private MotionEvent mDownEvent;
	// fields used for drawing shadow under a pinned section
	private GradientDrawable mShadowDrawable;
	private int mSectionsDistanceY;
	/**
	 * Scroll listener which does the magic
	 */
	private final OnScrollListener mOnScrollListener = new OnScrollListener() {

		@Override
		public void onScrollStateChanged(AbsListView view, int scrollState) {
			if (mDelegateOnScrollListener != null) { // delegate
				mDelegateOnScrollListener.onScrollStateChanged(view, scrollState);
			}
		}

		@Override
		public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
		                     int totalItemCount) {

			if (mDelegateOnScrollListener != null) { // delegate
				mDelegateOnScrollListener
						.onScroll(view, firstVisibleItem, visibleItemCount, totalItemCount);
			}

			// get expected adapter or fail fast
			ListAdapter adapter = getAdapter();
			if (adapter == null || visibleItemCount == 0) {
				// nothing to do
				return;
			}

			final boolean isFirstVisibleItemSection =
					isItemViewTypePinned(adapter, adapter.getItemViewType(firstVisibleItem));

			if (isFirstVisibleItemSection) {
				View sectionView = getChildAt(0);
				// view sticks to the top, no need for pinned shadow
				if (sectionView.getTop() == getPaddingTop()) {
					destroyPinnedShadow();
				} else { // section doesn't stick to the top, make sure we have a pinned shadow
					ensureShadowForPosition(firstVisibleItem, firstVisibleItem, visibleItemCount);
				}

			} else { // section is not at the first visible position
				int sectionPosition = findCurrentSectionPosition(firstVisibleItem);
				if (sectionPosition > -1) { // we have section position
					ensureShadowForPosition(sectionPosition, firstVisibleItem, visibleItemCount);
				} else { // there is no section for the first visible item, destroy shadow
					destroyPinnedShadow();
				}
			}
		}

		;

	};
	/**
	 * Default change observer.
	 */
	private final DataSetObserver mDataSetObserver = new DataSetObserver() {

		@Override
		public void onChanged() {
			recreatePinnedShadow();
		}

		@Override
		public void onInvalidated() {
			recreatePinnedShadow();
		}
	};
	private int mShadowHeight;

	public PinnedSectionListView(Context context, AttributeSet attrs) {
		super(context, attrs);
		initView();
	}

	public PinnedSectionListView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initView();
	}

	//-- constructors

	public static boolean isItemViewTypePinned(ListAdapter adapter, int viewType) {
		if (adapter instanceof HeaderViewListAdapter) {
			adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
		}
		return ((PinnedSectionListAdapter) adapter).isItemViewTypePinned(viewType);
	}

	private void initView() {
		setOnScrollListener(mOnScrollListener);
		setOnBottomReachedListener(mBottomReachedListener);
		mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
		initShadow(mIsShadow);
	}

	public void setShadowVisible(boolean visible) {
		initShadow(visible);
		if (mPinnedSection != null) {
			View v = mPinnedSection.view;
			invalidate(v.getLeft(), v.getTop(), v.getRight(), v.getBottom() + mShadowHeight);
		}
	}

	//-- public API methods

	public void initShadow(boolean visible) {
		if (visible) {
			if (mShadowDrawable == null) {
				mShadowDrawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
						new int[]{Color.parseColor("#ffa0a0a0"), Color.parseColor("#50a0a0a0"),
								Color.parseColor("#00a0a0a0")});
				mShadowHeight = (int) (8 * getResources().getDisplayMetrics().density);
			}
		} else {
			if (mShadowDrawable != null) {
				mShadowDrawable = null;
				mShadowHeight = 0;
			}
		}
	}

	//-- pinned section drawing methods

	/**
	 * Create shadow wrapper with a pinned view for a view at given position
	 */
	void createPinnedShadow(int position) {

		// try to recycle shadow
		PinnedSection pinnedShadow = mRecycleSection;
		mRecycleSection = null;

		// create new shadow, if needed
		if (pinnedShadow == null) {
			pinnedShadow = new PinnedSection();
		}
		// request new view using recycled view, if such
		View pinnedView =
				getAdapter().getView(position, pinnedShadow.view, PinnedSectionListView.this);

		// read layout parameters
		LayoutParams layoutParams = (LayoutParams) pinnedView.getLayoutParams();
		if (layoutParams == null) {
			layoutParams = (LayoutParams) generateDefaultLayoutParams();
			pinnedView.setLayoutParams(layoutParams);
		}

		int heightMode = MeasureSpec.getMode(layoutParams.height);
		int heightSize = MeasureSpec.getSize(layoutParams.height);

		if (heightMode == MeasureSpec.UNSPECIFIED) {
			heightMode = MeasureSpec.EXACTLY;
		}

		int maxHeight = getHeight() - getListPaddingTop() - getListPaddingBottom();
		if (heightSize > maxHeight) {
			heightSize = maxHeight;
		}

		// measure & layout
		int ws = MeasureSpec
				.makeMeasureSpec(getWidth() - getListPaddingLeft() - getListPaddingRight(),
						MeasureSpec.EXACTLY);
		int hs = MeasureSpec.makeMeasureSpec(heightSize, heightMode);
		pinnedView.measure(ws, hs);
		pinnedView.layout(0, 0, pinnedView.getMeasuredWidth(), pinnedView.getMeasuredHeight());
		mTranslateY = 0;

		// initialize pinned shadow
		pinnedShadow.view = pinnedView;
		pinnedShadow.position = position;
		pinnedShadow.id = getAdapter().getItemId(position);

		// store pinned shadow
		mPinnedSection = pinnedShadow;
	}

	/**
	 * Destroy shadow wrapper for currently pinned view
	 */
	void destroyPinnedShadow() {
		if (mPinnedSection != null) {
			// keep shadow for being recycled later
			mRecycleSection = mPinnedSection;
			mPinnedSection = null;
		}
	}

	/**
	 * Makes sure we have an actual pinned shadow for given position.
	 */
	void ensureShadowForPosition(int sectionPosition, int firstVisibleItem, int visibleItemCount) {
		// no need for creating shadow at all, we have a single visible item
		if (visibleItemCount < 2) {
			destroyPinnedShadow();
			return;
		}

		// invalidate shadow, if required
		if (mPinnedSection != null && mPinnedSection.position != sectionPosition) {
			destroyPinnedShadow();
		}

		// create shadow, if empty
		if (mPinnedSection == null) {
			createPinnedShadow(sectionPosition);
		}

		// align shadow according to next section position, if needed
		int nextPosition = sectionPosition + 1;
		if (nextPosition < getCount()) {
			int nextSectionPosition = findFirstVisibleSectionPosition(nextPosition,
					visibleItemCount - (nextPosition - firstVisibleItem));
			if (nextSectionPosition > -1) {
				View nextSectionView = getChildAt(nextSectionPosition - firstVisibleItem);
				final int bottom = mPinnedSection.view.getBottom() + getPaddingTop();
				mSectionsDistanceY = nextSectionView.getTop() - bottom;
				if (mSectionsDistanceY < 0) {
					// next section overlaps pinned shadow, move it up
					mTranslateY = mSectionsDistanceY;
				} else {
					// next section does not overlap with pinned, stick to top
					mTranslateY = 0;
				}
			} else {
				// no other sections are visible, stick to top
				mTranslateY = 0;
				mSectionsDistanceY = Integer.MAX_VALUE;
			}
		}

	}

	int findFirstVisibleSectionPosition(int firstVisibleItem, int visibleItemCount) {
		ListAdapter adapter = getAdapter();

		int adapterDataCount = adapter.getCount();
		// dataset has changed, no candidate
		if (getLastVisiblePosition() >= adapterDataCount) {
			return -1;
		}

		//added to prevent index Outofbound (in case)
		if (firstVisibleItem + visibleItemCount >= adapterDataCount) {
			visibleItemCount = adapterDataCount - firstVisibleItem;
		}

		for (int childIndex = 0; childIndex < visibleItemCount; childIndex++) {
			int position = firstVisibleItem + childIndex;
			int viewType = adapter.getItemViewType(position);
			if (isItemViewTypePinned(adapter, viewType)) {
				return position;
			}
		}
		return -1;
	}

	int findCurrentSectionPosition(int fromPosition) {
		ListAdapter adapter = getAdapter();

		// dataset has changed, no candidate
		if (fromPosition >= adapter.getCount()) {
			return -1;
		}

		if (adapter instanceof SectionIndexer) {
			// try fast way by asking section indexer
			SectionIndexer indexer = (SectionIndexer) adapter;
			int sectionPosition = indexer.getSectionForPosition(fromPosition);
			int itemPosition = indexer.getPositionForSection(sectionPosition);
			int typeView = adapter.getItemViewType(itemPosition);
			if (isItemViewTypePinned(adapter, typeView)) {
				return itemPosition;
			} // else, no luck
		}

		// try slow way by looking through to the next section item above
		for (int position = fromPosition; position >= 0; position--) {
			int viewType = adapter.getItemViewType(position);
			if (isItemViewTypePinned(adapter, viewType)) {
				return position;
			}
		}
		// no candidate found
		return -1;
	}

	void recreatePinnedShadow() {
		destroyPinnedShadow();
		ListAdapter adapter = getAdapter();
		if (adapter != null && adapter.getCount() > 0) {
			int firstVisiblePosition = getFirstVisiblePosition();
			int sectionPosition = findCurrentSectionPosition(firstVisiblePosition);
			if (sectionPosition == -1) {
				// no views to pin, exit
				return;
			}
			ensureShadowForPosition(sectionPosition, firstVisiblePosition,
					getLastVisiblePosition() - firstVisiblePosition);
		}
	}

	@Override
	public void setOnScrollListener(OnScrollListener listener) {
		if (listener == mOnScrollListener) {
			super.setOnScrollListener(listener);
		} else {
			mDelegateOnScrollListener = listener;
		}
	}

	@Override
	public void onRestoreInstanceState(Parcelable state) {
		super.onRestoreInstanceState(state);
		// restore pinned view after configuration change
		post(new Runnable() {

			@Override
			public void run() {
				recreatePinnedShadow();
			}
		});
	}

	@Override
	public void setAdapter(ListAdapter adapter) {
		// unregister observer at old adapter and register on new one
		ListAdapter oldAdapter = getAdapter();
		if (oldAdapter != null) {
			oldAdapter.unregisterDataSetObserver(mDataSetObserver);
		}
		if (adapter != null) {
			adapter.registerDataSetObserver(mDataSetObserver);
		}

		// destroy pinned shadow, if new adapter is not same as old one
		if (oldAdapter != adapter) {
			destroyPinnedShadow();
		}

		super.setAdapter(adapter);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		super.onLayout(changed, l, t, r, b);
		if (mPinnedSection != null) {
			int parentWidth = r - l - getPaddingLeft() - getPaddingRight();
			int shadowWidth = mPinnedSection.view.getWidth();
			if (parentWidth != shadowWidth) {
				recreatePinnedShadow();
			}
		}
	}

	@Override
	protected void dispatchDraw(@NonNull Canvas canvas) {
		super.dispatchDraw(canvas);

		if (mPinnedSection != null) {

			// prepare variables
			int pLeft = getListPaddingLeft();
			int pTop = getListPaddingTop();
			View view = mPinnedSection.view;

			// draw child
			canvas.save();

			int clipHeight = view.getHeight() +
					(mShadowDrawable == null ? 0 : Math.min(mShadowHeight, mSectionsDistanceY));
			canvas.clipRect(pLeft, pTop, pLeft + view.getWidth(), pTop + clipHeight);

			canvas.translate(pLeft, pTop + mTranslateY);
			drawChild(canvas, mPinnedSection.view, getDrawingTime());

			if (mShadowDrawable != null && mSectionsDistanceY > 0) {
				mShadowDrawable
						.setBounds(mPinnedSection.view.getLeft(), mPinnedSection.view.getBottom(),
								mPinnedSection.view.getRight(),
								mPinnedSection.view.getBottom() + mShadowHeight);
				mShadowDrawable.draw(canvas);
			}

			canvas.restore();
		}
	}

	@Override
	public boolean dispatchTouchEvent(@NonNull MotionEvent ev) {

		final float x = ev.getX();
		final float y = ev.getY();
		final int action = ev.getAction();

		// create touch target
		if (action == MotionEvent.ACTION_DOWN && mTouchTarget == null && mPinnedSection != null &&
				isPinnedViewTouched(mPinnedSection.view, x, y)) {

			// user touched pinned view
			mTouchTarget = mPinnedSection.view;
			mTouchPoint.x = x;
			mTouchPoint.y = y;

			// copy down event for eventually be used later
			mDownEvent = MotionEvent.obtain(ev);
		}

		// forward event to pinned view
		if (mTouchTarget != null) {
			if (isPinnedViewTouched(mTouchTarget, x, y)) {
				mTouchTarget.dispatchTouchEvent(ev);
			}

			// perform onClick on pinned view
			if (action == MotionEvent.ACTION_UP) {
				super.dispatchTouchEvent(ev);
				performPinnedItemClick();
				clearTouchTarget();

			} else if (action == MotionEvent.ACTION_CANCEL) { // cancel
				clearTouchTarget();

			} else if (action == MotionEvent.ACTION_MOVE) {
				if (Math.abs(y - mTouchPoint.y) > mTouchSlop) {

					// cancel sequence on touch target
					MotionEvent event = MotionEvent.obtain(ev);
					event.setAction(MotionEvent.ACTION_CANCEL);
					mTouchTarget.dispatchTouchEvent(event);
					event.recycle();

					// provide correct sequence to super class for further handling
					super.dispatchTouchEvent(mDownEvent);
					super.dispatchTouchEvent(ev);
					clearTouchTarget();

				}
			}

			return true;
		}

		// call super if this was not our pinned view
		return super.dispatchTouchEvent(ev);
	}

	//-- touch handling methods

	private boolean isPinnedViewTouched(View view, float x, float y) {
		view.getHitRect(mTouchRect);

		// by taping top or bottom padding, the list performs on click on a border item.
		// we don't add top padding here to keep behavior consistent.
		mTouchRect.top += mTranslateY;

		mTouchRect.bottom += mTranslateY + getPaddingTop();
		mTouchRect.left += getPaddingLeft();
		mTouchRect.right -= getPaddingRight();
		return mTouchRect.contains((int) x, (int) y);
	}

	private void clearTouchTarget() {
		mTouchTarget = null;
		if (mDownEvent != null) {
			mDownEvent.recycle();
			mDownEvent = null;
		}
	}

	private boolean performPinnedItemClick() {
		if (mPinnedSection == null) {
			return false;
		}

		OnItemClickListener listener = getOnItemClickListener();
		if (listener != null && getAdapter().isEnabled(mPinnedSection.position)) {
			View view = mPinnedSection.view;
			playSoundEffect(SoundEffectConstants.CLICK);
			if (view != null) {
				view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
			}
			listener.onItemClick(this, view, mPinnedSection.position, mPinnedSection.id);
			return true;
		}
		return false;
	}

	@Override
	protected void onScrollChanged(int l, int t, int oldl, int oldt) {
		View view = getChildAt(getChildCount() - 1);
		if (view == null) {
			return;
		}
		int diff = (view.getBottom() - (getHeight() + getScrollY()));

		OnBottomReachedListener listener = getOnBottomReachedListener();
		if (diff == 0 && listener != null) {
			listener.onBottomReached();
		}
		super.onScrollChanged(l, t, oldl, oldt);
	}

	public OnBottomReachedListener getOnBottomReachedListener() {
		return mBottomReachedListener;
	}

	public void setOnBottomReachedListener(OnBottomReachedListener onBottomReachedListener) {
		mBottomReachedListener = onBottomReachedListener;
	}

	/**
	 * List adapter to be implemented for being used with PinnedSectionListView adapter.
	 */
	public interface PinnedSectionListAdapter extends ListAdapter {

		/**
		 * This method shall return 'true' if views of given type has to be pinned.
		 */
		boolean isItemViewTypePinned(int viewType);
	}

	public interface OnBottomReachedListener {

		void onBottomReached();
	}

	/**
	 * Wrapper class for pinned section view and its position in the list.
	 */
	static class PinnedSection {

		public View view;
		public int position;
		public long id;
	}
}