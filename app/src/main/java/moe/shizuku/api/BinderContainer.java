package moe.shizuku.api;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/** 兼容 Shizuku Provider 协议的 Binder 容器。 */
public final class BinderContainer implements Parcelable {
    public final IBinder binder;

    public BinderContainer(IBinder binder) {
        this.binder = binder;
    }

    private BinderContainer(Parcel in) {
        binder = in.readStrongBinder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(binder);
    }

    public static final Creator<BinderContainer> CREATOR = new Creator<BinderContainer>() {
        @Override
        public BinderContainer createFromParcel(Parcel source) {
            return new BinderContainer(source);
        }

        @Override
        public BinderContainer[] newArray(int size) {
            return new BinderContainer[size];
        }
    };
}
