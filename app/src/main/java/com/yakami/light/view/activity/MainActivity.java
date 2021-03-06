package com.yakami.light.view.activity;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.flyco.tablayout.listener.OnTabSelectListener;
import com.igexin.sdk.PushManager;
import com.mikepenz.materialdrawer.Drawer;
import com.mikepenz.materialdrawer.DrawerBuilder;
import com.mikepenz.materialdrawer.model.DividerDrawerItem;
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem;
import com.mikepenz.materialdrawer.model.SwitchDrawerItem;
import com.mikepenz.materialdrawer.model.interfaces.IDrawerItem;
import com.trello.rxlifecycle.ActivityEvent;
import com.yakami.light.AppManager;
import com.yakami.light.DeviceManager;
import com.yakami.light.R;
import com.yakami.light.ServerAPI;
import com.yakami.light.adapter.HFRankAdapter;
import com.yakami.light.adapter.PagerAdapter;
import com.yakami.light.bean.BangumiRank;
import com.yakami.light.bean.DiscRank;
import com.yakami.light.bean.RawTimeRankContainer;
import com.yakami.light.bean.SettingProfile;
import com.yakami.light.bean.TimeRankContainer;
import com.yakami.light.bean.Version;
import com.yakami.light.event.Event;
import com.yakami.light.event.RxBus;
import com.yakami.light.service.CopyService;
import com.yakami.light.service.DiscRankService;
import com.yakami.light.service.SettingService;
import com.yakami.light.service.VersionService;
import com.yakami.light.utils.IntentHelper;
import com.yakami.light.utils.Tools;
import com.yakami.light.view.activity.base.BaseTransTabMainActivity;
import com.yakami.light.view.fragment.AboutFragment;
import com.yakami.light.view.fragment.InstructionFragment;
import com.yakami.light.view.fragment.RankDialogFragment;
import com.yakami.light.view.fragment.SettingsFragment;

import java.util.ArrayList;
import java.util.List;

import butterknife.Bind;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

import static com.yakami.light.AppManager.getDiscRankService;
import static com.yakami.light.service.SettingService.BLOCK;
import static com.yakami.light.service.SettingService.LIGHT;
import static com.yakami.light.service.SettingService.NIGHT_MODE;
import static com.yakami.light.service.SettingService.NOT_DISTURB;
import static com.yakami.light.service.SettingService.SINGLE;
import static com.yakami.light.service.SettingService.SOUND;
import static com.yakami.light.service.SettingService.VIBRATE;

/**
 * Created by Yakami on 2016/6/9, enjoying it!
 */
public class MainActivity extends BaseTransTabMainActivity
        implements OnTabSelectListener, Drawer.OnDrawerItemClickListener {

    @Bind(R.id.recyclerview) RecyclerView mRecyclerView;
    @Bind(R.id.search_layout) LinearLayout mSearchLayout;
    @Bind(R.id.edit_search) EditText mSearchEditText;

    private PagerAdapter mPagerAdapter;
    private HFRankAdapter mAdapter;
    private int mScrollY; //用于记录recyclerview的滑动位置
    private Drawer mDrawer;

    private Menu mMenu;
    private boolean mIsSearchMode;
    private String mKeyword;

    LinearLayoutManager mLayoutManager;

    @Override
    protected void onCreate(Bundle savedInstanceBundle) {
        super.onCreate(savedInstanceBundle);

        mTitle.setText(mRes.getText(R.string.app_name));

        initDrawer();

        initTabs(savedInstanceBundle != null);

        initRefresh();

        //getui init
        PushManager.getInstance().initialize(this.getApplicationContext());

        initSearch();

        mAdapter = new HFRankAdapter(this);
        mLayoutManager = new LinearLayoutManager(mContext);
        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.setAdapter(mAdapter);
        mAdapter.notifyDataSetChanged();

        mRecyclerView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                mScrollY += dy;
                int tmp = mScrollY / 2;
                //为了避免drawables间共享状态导致锁状态问题，得先使用mutate()进行变种处理
                mToolbar.getBackground().mutate().setAlpha(tmp > 255 ? 255 : tmp);
                mStatusBarBg.getBackground().mutate().setAlpha(tmp > 255 ? 255 : tmp);
            }
        });

        checkUpdate();
    }

    /**
     * 1. 避免在导航栏半透明情况下，覆盖recyclerview内容
     * 2. 动态调整内容过短至刚好填充屏幕。
     */
    private void setAboveNavigationBar(int height) {
        if (height == 0) {
            if (Build.VERSION.SDK_INT >= 21) {
                Event event = new Event();
                event.message = getNavigationBarHeight();
                event.type = Event.EventType.SET_ABOVE_NAVIGATION_BAR;
                RxBus.getInstance().send(event);
            } else {
                Event event = new Event();
                event.message = 0;
                event.type = Event.EventType.SET_ABOVE_NAVIGATION_BAR;
                RxBus.getInstance().send(event);
            }
        } else {
            Event event = new Event();
            event.message = height;
            event.type = Event.EventType.SET_ABOVE_NAVIGATION_BAR;
            RxBus.getInstance().send(event);
        }
    }


    @Override
    public void onBackPressed() {
        if (mDrawer.isDrawerOpen()) {
            mDrawer.closeDrawer();
        } else
            super.onBackPressed();
    }

    @Override
    protected int onBindLayout() {
        return R.layout.activity_main;
    }

    private void initRefresh() {
        RxBus.with(this)
                .setEvent(Event.EventType.REFRESH)
                .setEndEvent(ActivityEvent.DESTROY)
                .onNext(event -> onRefresh())
                .create();

        mRecyclerView.post(() -> {
            onRefresh();
            RxBus.getInstance().send(Event.EventType.SHOW_LOADING, null);
        });

    }

    public void onRefresh() {
        ServerAPI.getSakuraAPI()
                .getData()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(container -> {
                    getDiscRankService().setRankList(new ArrayList<>());
                    for (RawTimeRankContainer item : container) {
                        getDiscRankService().getTimeRanksList().add(new TimeRankContainer(item));
                    }
                    mPagerAdapter = new PagerAdapter(getSupportFragmentManager());
                    setTabLayout();
                    Tools.toast(mRes.getString(R.string.refresh_completed));
                    RxBus.getInstance().send(Event.EventType.REFRESH_COMPLETED, null);
                }, throwable -> {
                    throwable.printStackTrace();
                    Tools.toast(mRes.getString(R.string.network_or_server_error));
                    RxBus.getInstance().send(Event.EventType.REFRESH_COMPLETED, null);
                });
    }

    private void setTabLayout() {
        RxBus.getInstance().send(Event.EventType.INIT_TAB_LAYOUT, null);
        onTabSelect(AppManager.getTabPos());
        RxBus.with(this)
                .setEvent(Event.EventType.TAB_SELECT)
                .setEndEvent(ActivityEvent.DESTROY)
                .onNext(event -> onTabSelect(event.getMessage()))
                .create();
    }

    /**
     * 选择tab的响应, 显示数据
     *
     * @param position
     */
    @Override
    public void onTabSelect(int position) {
        //避免recyclerview过短，在tab切换的时候由于长度不足，造成瞬移回顶部。不足的底部填充一个screen的高度
        setAboveNavigationBar(getScreenSize().y);
        mAdapter.clear();
        List<BangumiRank> bangumiRankList = new ArrayList<>();

        if (position < getDiscRankService().getTimeRanksList().size())
            bangumiRankList = search(AppManager.getDiscRankService().getDiscRankList(position));
        else {
            //我的关注列表
            bangumiRankList = search(AppManager.getDiscRankService().getWatchedDiscRank());
        }

        mAdapter.addItem(bangumiRankList);
        mAdapter.notifyDataSetChanged();
        //长按开启通知配置dialog
        final List<BangumiRank> tmpList = (List<BangumiRank>) ((ArrayList) bangumiRankList).clone();
        mAdapter.setOnItemLongClickListener((pos, itemData) -> {
            RankDialogFragment dialog = new RankDialogFragment();
            dialog.show(getSupportFragmentManager(), "RANK_DIALOG");
            dialog.setBangumiRankInfo(tmpList.get(pos));
        });
        //点击打开DetailActivity
        mAdapter.setOnItemClickListener((pos, itemData) -> {
            BangumiRank rank = (BangumiRank) itemData;
            startActivity(IntentHelper
                    .newInstance(mActivityContext, DetailActivity.class)
                    .putSerializable("data", getDiscRankService().toDicsRank(rank))
                    .toIntent());
        });

        AppManager.setTabPos(position);

        addOnRunListener(() -> {
            mRecyclerView.post(() -> {
                int tmp = mRecyclerView.computeVerticalScrollRange() - getScreenSize().y;
                if (tmp > getScreenSize().y) {
                    setAboveNavigationBar(0);
                } else {
                    setAboveNavigationBar(getScreenSize().y);
                }
            });
        });
    }


    @Override
    public void onTabReselect(int position) {

    }

    /**
     * 初始化侧滑栏
     */
    protected void initDrawer() {
        SettingService service = AppManager.getSettingService();
        SettingProfile profile = service.getProfile();
        SwitchDrawerItem vibrate = new SwitchDrawerItem().withIdentifier(1)
                .withName(R.string.notification_vibrate)
                .withChecked(profile.isHasVibrate())
                .withOnCheckedChangeListener((drawerItem, buttonView, isChecked) -> service.setItemValue(VIBRATE, isChecked));
        SwitchDrawerItem sound = new SwitchDrawerItem().withIdentifier(2).
                withName(R.string.notification_sound).
                withChecked(profile.isHasSound())
                .withOnCheckedChangeListener((drawerItem, buttonView, isChecked) -> service.setItemValue(SOUND, isChecked));
        SwitchDrawerItem light = new SwitchDrawerItem().withIdentifier(3)
                .withName(R.string.notification_light)
                .withChecked(profile.isHasLight())
                .withOnCheckedChangeListener((drawerItem, buttonView, isChecked) -> service.setItemValue(LIGHT, isChecked));
        SwitchDrawerItem single = new SwitchDrawerItem().withIdentifier(4)
                .withName(R.string.notification_single)
                .withChecked(profile.isSingleNotification())
                .withOnCheckedChangeListener((drawerItem, buttonView, isChecked) -> service.setItemValue(SINGLE, isChecked));
        SwitchDrawerItem block = new SwitchDrawerItem().withIdentifier(5).
                withName(R.string.block_notification)
                .withChecked(profile.isBlocking())
                .withOnCheckedChangeListener((drawerItem, buttonView, isChecked) -> service.setItemValue(BLOCK, isChecked));
        SwitchDrawerItem night = new SwitchDrawerItem().withIdentifier(6).
                withName(R.string.night_mode)
                .withChecked(profile.isNightMode())
                .withOnCheckedChangeListener((drawerItem, buttonView, isChecked) -> service.setItemValue(NIGHT_MODE, isChecked));
        SwitchDrawerItem disturb = new SwitchDrawerItem().withIdentifier(11).
                withName(R.string.not_disturb)
                .withChecked(profile.isNotDisturb())
                .withOnCheckedChangeListener((drawerItem, buttonView, isChecked) -> service.setItemValue(NOT_DISTURB, isChecked));

        mDrawer = new DrawerBuilder()
                .withActivity(this)
                .withHeader(R.layout.view_nav_header)
                .withFooter(R.layout.view_nav_footer)
                .withToolbar(mToolbar)
                .addDrawerItems(
                        vibrate, sound, light, single,
                        new DividerDrawerItem(),
                        block, disturb,
                        new DividerDrawerItem(),
                        new PrimaryDrawerItem().withIdentifier(7).withName(mRes.getString(R.string.remove_all_notification)).withOnDrawerItemClickListener(this),
                        new DividerDrawerItem(),
                        new PrimaryDrawerItem().withIdentifier(11).withName(R.string.other_settings).withOnDrawerItemClickListener(this),
                        new DividerDrawerItem(),
                        new PrimaryDrawerItem().withIdentifier(8).withName(R.string.share).withOnDrawerItemClickListener(this),
                        new PrimaryDrawerItem().withIdentifier(9).withName(R.string.instruction).withOnDrawerItemClickListener(this),
                        new PrimaryDrawerItem().withIdentifier(10).withName(R.string.about).withOnDrawerItemClickListener(this))
                .build();

        //非常重要，用了transparent风格的话，得将drawerLayout的fitSystemWindows给关了，否则惨不忍睹
        if (Build.VERSION.SDK_INT >= 21) {
            mDrawer.getDrawerLayout().setFitsSystemWindows(false);
        }
    }

    @Override
    public boolean onItemClick(View view, int position, IDrawerItem drawerItem) {
        switch ((int) drawerItem.getIdentifier()) {
            case 7: //移除所有推送和关注
                AppManager.getNotificationService().removeAllNotification();
                Tools.toast(mRes.getString(R.string.remove_completed));
                return false;
            case 8: //share
                share(mRes.getString(R.string.app_name), mRes.getString(R.string.share_text));
                break;
            case 9: //instruction
                startActivity(IntentHelper.newInstance(mActivityContext, SingleFragmentActivity.class)
                        .putString("class", InstructionFragment.class.toString())
                        .putString("title", mRes.getString(R.string.instruction))
                        .toIntent());
                return true;
            case 10: //about
                startActivity(IntentHelper.newInstance(mActivityContext, SingleFragmentActivity.class)
                        .putString("class", AboutFragment.class.toString())
                        .putString("title", mRes.getString(R.string.about))
                        .toIntent());
                return true;
            case 11: //other settings
                startActivity(IntentHelper.newInstance(mActivityContext, SingleFragmentActivity.class)
                        .putString("class", SettingsFragment.class.toString())
                        .putString("title", mRes.getString(R.string.other_settings))
                        .toIntent());
                return true;
        }
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mMenu = menu;
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_instruction) {
            startActivity(IntentHelper.newInstance(mActivityContext, SingleFragmentActivity.class)
                    .putString("class", InstructionFragment.class.toString())
                    .putString("title", mRes.getString(R.string.instruction))
                    .toIntent());
            return true;
        }

        if (id == R.id.action_search) {
            mIsSearchMode = !mIsSearchMode;
            switchSearchMode(mIsSearchMode, R.id.action_search);
            return true;
        }

        if (id == R.id.action_copy_visible_item) {
            int start = mLayoutManager.findFirstVisibleItemPosition();
            int end = mLayoutManager.findLastVisibleItemPosition();
            int pos = AppManager.getTabPos();
            List<DiscRank> list = new ArrayList<>();
            if (!AppManager.isWatchedList())
                list = AppManager.getDiscRankService().getTimeRanksList().get(pos).getDiscs();
            else
                list = AppManager.getDiscRankService().getWatchedDiscRank();
            if (end == list.size() + 1)
                end = list.size();
            list = list.subList(start, end);
            DeviceManager.copyToClipboard(CopyService.copyRank(list));
            Tools.toast(mRes.getString(R.string.copy_completed));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    protected void checkUpdate() {
        //不是通知式更新
        if (VersionService.getInstance().getVersion() == null) {
            VersionService.getInstance().checkVersionUpdate();

            RxBus.with(this)
                    .setEvent(Event.EventType.VERSION_DIALOG)
                    .setEndEvent(ActivityEvent.DESTROY)
                    .onNext(event -> {
                        showDialog(event.getMessage());
                    })
                    .create();
        } else {
            showDialog(VersionService.getInstance().getVersion());
            VersionService.getInstance().setVersion(null);
        }

    }

    private void showDialog(Version version) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(mActivityContext);
        dialog.setTitle("有新版本！ v" + version.getVersion());
        dialog.setMessage(version.getIntro());
        dialog.setCancelable(true);
        dialog.setPositiveButton("更新", (dialogInterface, which) -> {
            OpenUrl(version.getUrl());
//            Tools.toast("后台下载中");
//            ApkUpdateUtils.download(this, version.getUrl(), getResources().getString(R.string.app_name));
        });
        dialog.show();
    }

    private void switchSearchMode(boolean isSearchMode, int id) {
        mTitle.setVisibility(isSearchMode ? View.GONE : View.VISIBLE);
        mSearchLayout.setVisibility(isSearchMode ? View.VISIBLE : View.GONE);
        mMenu.getItem(2).setIcon(isSearchMode ? R.drawable.ic_close_white_24dp : R.drawable.ic_search_white_24dp);
        if (isSearchMode) {
            mSearchEditText.requestFocus();
            DeviceManager.getSoftInputManager(mContext).toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        } else {
            DeviceManager.hideSoftInput(mContext, getCurrentFocus());
            mKeyword = "";
            onTabSelect(AppManager.getTabPos());
        }
    }

    private void initSearch() {
        mSearchEditText.setOnEditorActionListener((textView, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        reInitToolbarAlpha();
                        mKeyword = textView.getText().toString();
                        onTabSelect(AppManager.getTabPos());
                        DeviceManager.hideSoftInput(mContext, getCurrentFocus());
                        return true;
                    }
                    return false;
                }
        );
    }

    /**
     * adpater改变时，高度若是从满一screen到不足一screen，则toolbar的渐变效果计算会出错，此方法用于重置计算
     */
    private void reInitToolbarAlpha() {
        mRecyclerView.scrollToPosition(0);
        mScrollY = 0;
        mToolbar.getBackground().mutate().setAlpha(0);
        mStatusBarBg.getBackground().mutate().setAlpha(0);
    }

    private List<BangumiRank> search(List<DiscRank> list) {
        if (Tools.isAvailableStr(mKeyword)) {
            List<DiscRank> result = new ArrayList<>();
            for (DiscRank item : list) {
                String name = item.getName().toLowerCase();
                String sName = item.getsName().toLowerCase();
                String lowerKeyword = mKeyword.toLowerCase();
                if (name.contains(lowerKeyword) || sName.contains(lowerKeyword)) {
                    result.add(item);
                }
            }
            return DiscRankService.toBangumiRank(result);
        } else
            return DiscRankService.toBangumiRank(list);
    }
}
