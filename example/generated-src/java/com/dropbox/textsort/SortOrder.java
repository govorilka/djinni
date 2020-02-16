// AUTOGENERATED FILE - DO NOT MODIFY!
// This file generated by Djinni from example.djinni

package com.dropbox.textsort;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

/*package*/ enum SortOrder implements Parcelable {
    ASCENDING,
    DESCENDING,
    RANDOM,
    ;

    @Override
    public @StringRes int getLabelId() {
        switch(this)
        {
            case ASCENDING:
                return R.string.sort_order_ascending;
            case DESCENDING:
                return R.string.sort_order_descending;
            case RANDOM:
                return R.string.sort_order_random;
            default:
                return 0;
        }
    }

    @Override
    public @DrawableRes int getIconId() {
        switch(this)
        {
            case ASCENDING:
                return R.drawable.sort_order_ascending;
            case DESCENDING:
                return R.drawable.sort_order_descending;
            case RANDOM:
                return R.drawable.sort_order_random;
            default:
                return 0;
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        dest.writeString(name());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<SortOrder> CREATOR = new Creator<SortOrder>()
    {
        @Override
        public SortOrder createFromParcel(Parcel in) {
            return SortOrder.valueOf(in.readString());
        }

        @Override
        public SortOrder[] newArray(int size) {
            return new SortOrder[size];
        }
    };
}
