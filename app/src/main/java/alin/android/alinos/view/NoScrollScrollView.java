package alin.android.alinos.view;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * 不滚动的 ScrollView。
 *
 * 用途：外层容器需要 {@code fillViewport} 的测量行为（子布局撑满窗口），
 * 但不允许自身滚动——滚动交给内部子 View（如 ListView / 内层 ScrollView）。
 *
 * 重点解决两个问题：
 * 1. 触摸事件被外层截获，导致内部列表无法滑动 → onInterceptTouchEvent 返回 false
 * 2. 长按文本全选/移动光标时，子 View 请求父容器滚动，导致整页滚到底且滚不回来
 *    → requestChildRectangleOnScreen 返回 false
 */
public class NoScrollScrollView extends ScrollView {

    public NoScrollScrollView(Context context) {
        super(context);
    }

    public NoScrollScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NoScrollScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;   // 永不拦截，事件全部交给子 View
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return false;   // 自身也不处理滚动
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return false;
    }

    /**
     * 拒绝子 View 的"滚动到可见"请求。
     * 文本选择（全选/拖选）会触发此调用，若放行会把整个窗口滚到底部。
     */
    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        return false;
    }

    /** 禁止通过代码滚动（始终锁定在顶部）。 */
    @Override
    public void scrollTo(int x, int y) {
        super.scrollTo(x, 0);
    }
}
