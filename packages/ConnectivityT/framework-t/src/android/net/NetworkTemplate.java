/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import static android.net.ConnectivityManager.TYPE_BLUETOOTH;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_PROXY;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIFI_P2P;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.NetworkIdentity.OEM_NONE;
import static android.net.NetworkIdentity.OEM_PAID;
import static android.net.NetworkIdentity.OEM_PRIVATE;
import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.DEFAULT_NETWORK_YES;
import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkStats.ROAMING_ALL;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.ROAMING_YES;
import static android.net.wifi.WifiInfo.sanitizeSsid;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.NetworkType;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.util.ArrayUtils;
import com.android.net.module.util.NetworkIdentityUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Predicate used to match {@link NetworkIdentity}, usually when collecting
 * statistics. (It should probably have been named {@code NetworkPredicate}.)
 *
 * @hide
 */
// @SystemApi(client = MODULE_LIBRARIES)
public final class NetworkTemplate implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "MATCH_" }, value = {
            MATCH_MOBILE,
            MATCH_WIFI,
            MATCH_ETHERNET,
            MATCH_BLUETOOTH,
            MATCH_CARRIER
    })
    public @interface TemplateMatchRule{}

    /** Match rule to match cellular networks with given Subscriber Ids. */
    public static final int MATCH_MOBILE = 1;
    /** Match rule to match wifi networks. */
    public static final int MATCH_WIFI = 4;
    /** Match rule to match ethernet networks. */
    public static final int MATCH_ETHERNET = 5;
    /**
     * Match rule to match all cellular networks.
     *
     * @hide
     */
    public static final int MATCH_MOBILE_WILDCARD = 6;
    /**
     * Match rule to match all wifi networks.
     *
     * @hide
     */
    public static final int MATCH_WIFI_WILDCARD = 7;
    /** Match rule to match bluetooth networks. */
    public static final int MATCH_BLUETOOTH = 8;
    /**
     * Match rule to match networks with {@link Connectivity#TYPE_PROXY} as the legacy network type.
     *
     * @hide
     */
    public static final int MATCH_PROXY = 9;
    /**
     * Match rule to match all networks with subscriberId inside the template. Some carriers
     * may offer non-cellular networks like WiFi, which will be matched by this rule.
     */
    public static final int MATCH_CARRIER = 10;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SUBSCRIBER_ID_MATCH_RULE_" }, value = {
            SUBSCRIBER_ID_MATCH_RULE_EXACT,
            SUBSCRIBER_ID_MATCH_RULE_ALL
    })
    public @interface SubscriberIdMatchRule{}
    /**
     * Value of the match rule of the subscriberId to match networks with specific subscriberId.
     */
    public static final int SUBSCRIBER_ID_MATCH_RULE_EXACT = 0;
    /**
     * Value of the match rule of the subscriberId to match networks with any subscriberId which
     * includes null and non-null.
     */
    public static final int SUBSCRIBER_ID_MATCH_RULE_ALL = 1;

    /**
     * Wi-Fi Network ID is never supposed to be null (if it is, it is a bug that
     * should be fixed), so it's not possible to want to match null vs
     * non-null. Therefore it's fine to use null as a sentinel for Network ID.
     */
    public static final String WIFI_NETWORKID_ALL = null;

    /**
     * Include all network types when filtering. This is meant to merge in with the
     * {@code TelephonyManager.NETWORK_TYPE_*} constants, and thus needs to stay in sync.
     */
    public static final int NETWORK_TYPE_ALL = -1;
    /**
     * Virtual RAT type to represent 5G NSA (Non Stand Alone) mode, where the primary cell is
     * still LTE and network allocates a secondary 5G cell so telephony reports RAT = LTE along
     * with NR state as connected. This should not be overlapped with any of the
     * {@code TelephonyManager.NETWORK_TYPE_*} constants.
     *
     * @hide
     */
    public static final int NETWORK_TYPE_5G_NSA = -2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "OEM_MANAGED_" }, value = {
            OEM_MANAGED_ALL,
            OEM_MANAGED_NO,
            OEM_MANAGED_YES,
            OEM_MANAGED_PAID,
            OEM_MANAGED_PRIVATE
    })
    public @interface OemManaged{}

    /**
     * Value to match both OEM managed and unmanaged networks (all networks).
     */
    public static final int OEM_MANAGED_ALL = -1;
    /**
     * Value to match networks which are not OEM managed.
     */
    public static final int OEM_MANAGED_NO = OEM_NONE;
    /**
     * Value to match any OEM managed network.
     */
    public static final int OEM_MANAGED_YES = -2;
    /**
     * Network has {@link NetworkCapabilities#NET_CAPABILITY_OEM_PAID}.
     */
    public static final int OEM_MANAGED_PAID = OEM_PAID;
    /**
     * Network has {@link NetworkCapabilities#NET_CAPABILITY_OEM_PRIVATE}.
     */
    public static final int OEM_MANAGED_PRIVATE = OEM_PRIVATE;

    private static boolean isKnownMatchRule(final int rule) {
        switch (rule) {
            case MATCH_MOBILE:
            case MATCH_WIFI:
            case MATCH_ETHERNET:
            case MATCH_MOBILE_WILDCARD:
            case MATCH_WIFI_WILDCARD:
            case MATCH_BLUETOOTH:
            case MATCH_PROXY:
            case MATCH_CARRIER:
                return true;

            default:
                return false;
        }
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_MOBILE} networks with
     * the given IMSI.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateMobileAll(String subscriberId) {
        return new NetworkTemplate(MATCH_MOBILE, subscriberId, null);
    }

    /**
     * Template to match cellular networks with the given IMSI, {@code ratType} and
     * {@code metered}. Use {@link #NETWORK_TYPE_ALL} to include all network types when
     * filtering. See {@code TelephonyManager.NETWORK_TYPE_*}.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateMobileWithRatType(@Nullable String subscriberId,
            @NetworkType int ratType, int metered) {
        if (TextUtils.isEmpty(subscriberId)) {
            return new NetworkTemplate(MATCH_MOBILE_WILDCARD, null, null, null,
                    metered, ROAMING_ALL, DEFAULT_NETWORK_ALL, ratType, OEM_MANAGED_ALL,
                    SUBSCRIBER_ID_MATCH_RULE_EXACT);
        }
        return new NetworkTemplate(MATCH_MOBILE, subscriberId, new String[]{subscriberId}, null,
                metered, ROAMING_ALL, DEFAULT_NETWORK_ALL, ratType, OEM_MANAGED_ALL,
                SUBSCRIBER_ID_MATCH_RULE_EXACT);
    }

    /**
     * Template to match metered {@link ConnectivityManager#TYPE_MOBILE} networks,
     * regardless of IMSI.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static NetworkTemplate buildTemplateMobileWildcard() {
        return new NetworkTemplate(MATCH_MOBILE_WILDCARD, null, null);
    }

    /**
     * Template to match all metered {@link ConnectivityManager#TYPE_WIFI} networks,
     * regardless of SSID.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateWifiWildcard() {
        // TODO: Consider replace this with MATCH_WIFI with NETWORK_ID_ALL
        // and SUBSCRIBER_ID_MATCH_RULE_ALL.
        return new NetworkTemplate(MATCH_WIFI_WILDCARD, null, null);
    }

    /** @hide */
    @Deprecated
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateWifi() {
        return buildTemplateWifiWildcard();
    }

    /**
     * Template to match {@link ConnectivityManager#TYPE_WIFI} networks with the
     * given SSID.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateWifi(@NonNull String networkId) {
        Objects.requireNonNull(networkId);
        return new NetworkTemplate(MATCH_WIFI, null /* subscriberId */,
                new String[] { null } /* matchSubscriberIds */,
                networkId, METERED_ALL, ROAMING_ALL,
                DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL, OEM_MANAGED_ALL,
                SUBSCRIBER_ID_MATCH_RULE_ALL);
    }

    /**
     * Template to match all {@link ConnectivityManager#TYPE_WIFI} networks with the given SSID,
     * and IMSI.
     *
     * Call with {@link #WIFI_NETWORKID_ALL} for {@code networkId} to get result regardless of SSID.
     */
    public static NetworkTemplate buildTemplateWifi(@Nullable String networkId,
            @Nullable String subscriberId) {
        return new NetworkTemplate(MATCH_WIFI, subscriberId, new String[] { subscriberId },
                networkId, METERED_ALL, ROAMING_ALL,
                DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL, OEM_MANAGED_ALL,
                SUBSCRIBER_ID_MATCH_RULE_EXACT);
    }

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_ETHERNET} style
     * networks together.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static NetworkTemplate buildTemplateEthernet() {
        return new NetworkTemplate(MATCH_ETHERNET, null, null);
    }

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_BLUETOOTH} style
     * networks together.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateBluetooth() {
        return new NetworkTemplate(MATCH_BLUETOOTH, null, null);
    }

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_PROXY} style
     * networks together.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateProxy() {
        return new NetworkTemplate(MATCH_PROXY, null, null);
    }

    /**
     * Template to match all metered carrier networks with the given IMSI.
     *
     * @hide
     */
    public static NetworkTemplate buildTemplateCarrierMetered(@NonNull String subscriberId) {
        Objects.requireNonNull(subscriberId);
        return new NetworkTemplate(MATCH_CARRIER, subscriberId,
                new String[] { subscriberId }, null /* networkId */, METERED_YES, ROAMING_ALL,
                DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL, OEM_MANAGED_ALL,
                SUBSCRIBER_ID_MATCH_RULE_EXACT);
    }

    private final int mMatchRule;
    private final String mSubscriberId;

    /**
     * Ugh, templates are designed to target a single subscriber, but we might
     * need to match several "merged" subscribers. These are the subscribers
     * that should be considered to match this template.
     * <p>
     * Since the merge set is dynamic, it should <em>not</em> be persisted or
     * used for determining equality.
     */
    private final String[] mMatchSubscriberIds;

    private final String mNetworkId;

    // Matches for the NetworkStats constants METERED_*, ROAMING_* and DEFAULT_NETWORK_*.
    private final int mMetered;
    private final int mRoaming;
    private final int mDefaultNetwork;
    private final int mSubType;
    /**
     * The subscriber Id match rule defines how the template should match networks with
     * specific subscriberId(s). See NetworkTemplate#SUBSCRIBER_ID_MATCH_RULE_* for more detail.
     */
    private final int mSubscriberIdMatchRule;

    // Bitfield containing OEM network properties{@code NetworkIdentity#OEM_*}.
    private final int mOemManaged;

    private void checkValidSubscriberIdMatchRule() {
        switch (mMatchRule) {
            case MATCH_MOBILE:
            case MATCH_CARRIER:
                // MOBILE and CARRIER templates must always specify a subscriber ID.
                if (mSubscriberIdMatchRule == SUBSCRIBER_ID_MATCH_RULE_ALL) {
                    throw new IllegalArgumentException("Invalid SubscriberIdMatchRule"
                            + "on match rule: " + getMatchRuleName(mMatchRule));
                }
                return;
            default:
                return;
        }
    }

    /** @hide */
    // TODO: Deprecate this constructor, mark it @UnsupportedAppUsage(maxTargetSdk = S)
    @UnsupportedAppUsage
    public NetworkTemplate(int matchRule, String subscriberId, String networkId) {
        this(matchRule, subscriberId, new String[] { subscriberId }, networkId);
    }

    /** @hide */
    public NetworkTemplate(int matchRule, String subscriberId, String[] matchSubscriberIds,
            String networkId) {
        // Older versions used to only match MATCH_MOBILE and MATCH_MOBILE_WILDCARD templates
        // to metered networks. It is now possible to match mobile with any meteredness, but
        // in order to preserve backward compatibility of @UnsupportedAppUsage methods, this
        //constructor passes METERED_YES for these types.
        this(matchRule, subscriberId, matchSubscriberIds, networkId,
                (matchRule == MATCH_MOBILE || matchRule == MATCH_MOBILE_WILDCARD) ? METERED_YES
                : METERED_ALL , ROAMING_ALL, DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL,
                OEM_MANAGED_ALL, SUBSCRIBER_ID_MATCH_RULE_EXACT);
    }

    /** @hide */
    // TODO: Remove it after updating all of the caller.
    public NetworkTemplate(int matchRule, String subscriberId, String[] matchSubscriberIds,
            String networkId, int metered, int roaming, int defaultNetwork, int subType,
            int oemManaged) {
        this(matchRule, subscriberId, matchSubscriberIds, networkId, metered, roaming,
                defaultNetwork, subType, oemManaged, SUBSCRIBER_ID_MATCH_RULE_EXACT);
    }

    /** @hide */
    public NetworkTemplate(int matchRule, String subscriberId, String[] matchSubscriberIds,
            String networkId, int metered, int roaming, int defaultNetwork, int subType,
            int oemManaged, int subscriberIdMatchRule) {
        mMatchRule = matchRule;
        mSubscriberId = subscriberId;
        // TODO: Check whether mMatchSubscriberIds = null or mMatchSubscriberIds = {null} when
        // mSubscriberId is null
        mMatchSubscriberIds = matchSubscriberIds;
        mNetworkId = networkId;
        mMetered = metered;
        mRoaming = roaming;
        mDefaultNetwork = defaultNetwork;
        mSubType = subType;
        mOemManaged = oemManaged;
        mSubscriberIdMatchRule = subscriberIdMatchRule;
        checkValidSubscriberIdMatchRule();
        if (!isKnownMatchRule(matchRule)) {
            throw new IllegalArgumentException("Unknown network template rule " + matchRule
                    + " will not match any identity.");
        }
    }

    private NetworkTemplate(Parcel in) {
        mMatchRule = in.readInt();
        mSubscriberId = in.readString();
        mMatchSubscriberIds = in.createStringArray();
        mNetworkId = in.readString();
        mMetered = in.readInt();
        mRoaming = in.readInt();
        mDefaultNetwork = in.readInt();
        mSubType = in.readInt();
        mOemManaged = in.readInt();
        mSubscriberIdMatchRule = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMatchRule);
        dest.writeString(mSubscriberId);
        dest.writeStringArray(mMatchSubscriberIds);
        dest.writeString(mNetworkId);
        dest.writeInt(mMetered);
        dest.writeInt(mRoaming);
        dest.writeInt(mDefaultNetwork);
        dest.writeInt(mSubType);
        dest.writeInt(mOemManaged);
        dest.writeInt(mSubscriberIdMatchRule);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("NetworkTemplate: ");
        builder.append("matchRule=").append(getMatchRuleName(mMatchRule));
        if (mSubscriberId != null) {
            builder.append(", subscriberId=").append(
                    NetworkIdentityUtils.scrubSubscriberId(mSubscriberId));
        }
        if (mMatchSubscriberIds != null) {
            builder.append(", matchSubscriberIds=").append(
                    Arrays.toString(NetworkIdentityUtils.scrubSubscriberIds(mMatchSubscriberIds)));
        }
        if (mNetworkId != null) {
            builder.append(", networkId=").append(mNetworkId);
        }
        if (mMetered != METERED_ALL) {
            builder.append(", metered=").append(NetworkStats.meteredToString(mMetered));
        }
        if (mRoaming != ROAMING_ALL) {
            builder.append(", roaming=").append(NetworkStats.roamingToString(mRoaming));
        }
        if (mDefaultNetwork != DEFAULT_NETWORK_ALL) {
            builder.append(", defaultNetwork=").append(NetworkStats.defaultNetworkToString(
                    mDefaultNetwork));
        }
        if (mSubType != NETWORK_TYPE_ALL) {
            builder.append(", subType=").append(mSubType);
        }
        if (mOemManaged != OEM_MANAGED_ALL) {
            builder.append(", oemManaged=").append(getOemManagedNames(mOemManaged));
        }
        builder.append(", subscriberIdMatchRule=")
                .append(subscriberIdMatchRuleToString(mSubscriberIdMatchRule));
        return builder.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMatchRule, mSubscriberId, mNetworkId, mMetered, mRoaming,
                mDefaultNetwork, mSubType, mOemManaged, mSubscriberIdMatchRule);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof NetworkTemplate) {
            final NetworkTemplate other = (NetworkTemplate) obj;
            return mMatchRule == other.mMatchRule
                    && Objects.equals(mSubscriberId, other.mSubscriberId)
                    && Objects.equals(mNetworkId, other.mNetworkId)
                    && mMetered == other.mMetered
                    && mRoaming == other.mRoaming
                    && mDefaultNetwork == other.mDefaultNetwork
                    && mSubType == other.mSubType
                    && mOemManaged == other.mOemManaged
                    && mSubscriberIdMatchRule == other.mSubscriberIdMatchRule;
        }
        return false;
    }

    private String subscriberIdMatchRuleToString(int rule) {
        switch (rule) {
            case SUBSCRIBER_ID_MATCH_RULE_EXACT:
                return "EXACT_MATCH";
            case SUBSCRIBER_ID_MATCH_RULE_ALL:
                return "ALL";
            default:
                return "Unknown rule " + rule;
        }
    }

    /** @hide */
    public boolean isMatchRuleMobile() {
        switch (mMatchRule) {
            case MATCH_MOBILE:
            case MATCH_MOBILE_WILDCARD:
                return true;
            default:
                return false;
        }
    }

    /**
     * Check if the template can be persisted into disk.
     *
     * @hide
     */
    // TODO: Move to the NetworkPolicy.
    public boolean isPersistable() {
        switch (mMatchRule) {
            case MATCH_MOBILE_WILDCARD:
            case MATCH_WIFI_WILDCARD:
                return false;
            case MATCH_CARRIER:
                return mSubscriberId != null;
            case MATCH_WIFI:
                if (Objects.equals(mNetworkId, WIFI_NETWORKID_ALL)
                        && mSubscriberIdMatchRule == SUBSCRIBER_ID_MATCH_RULE_ALL) {
                    return false;
                }
                return true;
            default:
                return true;
        }
    }

    /**
     * Get match rule of the template. See {@code MATCH_*}.
     */
    @UnsupportedAppUsage
    public int getMatchRule() {
        return mMatchRule;
    }

    /**
     * Get subscriber Id of the template.
     */
    @Nullable
    @UnsupportedAppUsage
    public String getSubscriberId() {
        return mSubscriberId;
    }

    public String getNetworkId() {
        return mNetworkId;
    }

    /**
     * Get Subscriber Id Match Rule of the template.
     */
    public int getSubscriberIdMatchRule() {
        return mSubscriberIdMatchRule;
    }

    /**
     * Get meteredness filter of the template.
     */
    @NetworkStats.Meteredness
    public int getMeteredness() {
        return mMetered;
    }

    /**
     * Test if given {@link NetworkIdentity} matches this template.
     *
     * @hide
     */
    public boolean matches(NetworkIdentity ident) {
        if (!matchesMetered(ident)) return false;
        if (!matchesRoaming(ident)) return false;
        if (!matchesDefaultNetwork(ident)) return false;
        if (!matchesOemNetwork(ident)) return false;

        switch (mMatchRule) {
            case MATCH_MOBILE:
                return matchesMobile(ident);
            case MATCH_WIFI:
                return matchesWifi(ident);
            case MATCH_ETHERNET:
                return matchesEthernet(ident);
            case MATCH_MOBILE_WILDCARD:
                return matchesMobileWildcard(ident);
            case MATCH_WIFI_WILDCARD:
                return matchesWifiWildcard(ident);
            case MATCH_BLUETOOTH:
                return matchesBluetooth(ident);
            case MATCH_PROXY:
                return matchesProxy(ident);
            case MATCH_CARRIER:
                return matchesCarrier(ident);
            default:
                // We have no idea what kind of network template we are, so we
                // just claim not to match anything.
                return false;
        }
    }

    private boolean matchesMetered(NetworkIdentity ident) {
        return (mMetered == METERED_ALL)
            || (mMetered == METERED_YES && ident.mMetered)
            || (mMetered == METERED_NO && !ident.mMetered);
    }

    private boolean matchesRoaming(NetworkIdentity ident) {
        return (mRoaming == ROAMING_ALL)
            || (mRoaming == ROAMING_YES && ident.mRoaming)
            || (mRoaming == ROAMING_NO && !ident.mRoaming);
    }

    private boolean matchesDefaultNetwork(NetworkIdentity ident) {
        return (mDefaultNetwork == DEFAULT_NETWORK_ALL)
            || (mDefaultNetwork == DEFAULT_NETWORK_YES && ident.mDefaultNetwork)
            || (mDefaultNetwork == DEFAULT_NETWORK_NO && !ident.mDefaultNetwork);
    }

    private boolean matchesOemNetwork(NetworkIdentity ident) {
        return (mOemManaged == OEM_MANAGED_ALL)
            || (mOemManaged == OEM_MANAGED_YES
                    && ident.mOemManaged != OEM_NONE)
            || (mOemManaged == ident.mOemManaged);
    }

    private boolean matchesCollapsedRatType(NetworkIdentity ident) {
        return mSubType == NETWORK_TYPE_ALL
                || getCollapsedRatType(mSubType) == getCollapsedRatType(ident.mSubType);
    }

    /**
     * Check if this template matches {@code subscriberId}. Returns true if this
     * template was created with {@code SUBSCRIBER_ID_MATCH_RULE_ALL}, or with a
     * {@code mMatchSubscriberIds} array that contains {@code subscriberId}.
     *
     * @hide
     */
    public boolean matchesSubscriberId(@Nullable String subscriberId) {
        return mSubscriberIdMatchRule == SUBSCRIBER_ID_MATCH_RULE_ALL
                || ArrayUtils.contains(mMatchSubscriberIds, subscriberId);
    }

    /**
     * Check if network with matching SSID. Returns true when the SSID matches, or when
     * {@code mNetworkId} is {@code WIFI_NETWORKID_ALL}.
     */
    private boolean matchesWifiNetworkId(@Nullable String networkId) {
        return Objects.equals(mNetworkId, WIFI_NETWORKID_ALL)
                || Objects.equals(sanitizeSsid(mNetworkId), sanitizeSsid(networkId));
    }

    /**
     * Check if mobile network with matching IMSI.
     */
    private boolean matchesMobile(NetworkIdentity ident) {
        if (ident.mType == TYPE_WIMAX) {
            // TODO: consider matching against WiMAX subscriber identity
            return true;
        } else {
            return ident.mType == TYPE_MOBILE && !ArrayUtils.isEmpty(mMatchSubscriberIds)
                    && ArrayUtils.contains(mMatchSubscriberIds, ident.mSubscriberId)
                    && matchesCollapsedRatType(ident);
        }
    }

    /**
     * Get a Radio Access Technology(RAT) type that is representative of a group of RAT types.
     * The mapping is corresponding to {@code TelephonyManager#NETWORK_CLASS_BIT_MASK_*}.
     *
     * @param ratType An integer defined in {@code TelephonyManager#NETWORK_TYPE_*}.
     *
     * @hide
     */
    // TODO: 1. Consider move this to TelephonyManager if used by other modules.
    //       2. Consider make this configurable.
    //       3. Use TelephonyManager APIs when available.
    // TODO: @SystemApi when ready.
    public static int getCollapsedRatType(int ratType) {
        switch (ratType) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_GSM:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_IDEN:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return TelephonyManager.NETWORK_TYPE_GSM;
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return TelephonyManager.NETWORK_TYPE_UMTS;
            case TelephonyManager.NETWORK_TYPE_LTE:
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return TelephonyManager.NETWORK_TYPE_LTE;
            case TelephonyManager.NETWORK_TYPE_NR:
                return TelephonyManager.NETWORK_TYPE_NR;
            // Virtual RAT type for 5G NSA mode, see {@link NetworkTemplate#NETWORK_TYPE_5G_NSA}.
            case NetworkTemplate.NETWORK_TYPE_5G_NSA:
                return NetworkTemplate.NETWORK_TYPE_5G_NSA;
            default:
                return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
    }

    /**
     * Return all supported collapsed RAT types that could be returned by
     * {@link #getCollapsedRatType(int)}.
     *
     * @hide
     */
    // TODO: @SystemApi when ready.
    @NonNull
    public static final int[] getAllCollapsedRatTypes() {
        final int[] ratTypes = TelephonyManager.getAllNetworkTypes();
        final HashSet<Integer> collapsedRatTypes = new HashSet<>();
        for (final int ratType : ratTypes) {
            collapsedRatTypes.add(NetworkTemplate.getCollapsedRatType(ratType));
        }
        // Add NETWORK_TYPE_5G_NSA to the returned list since 5G NSA is a virtual RAT type and
        // it is not in TelephonyManager#NETWORK_TYPE_* constants.
        // See {@link NetworkTemplate#NETWORK_TYPE_5G_NSA}.
        collapsedRatTypes.add(NetworkTemplate.getCollapsedRatType(NETWORK_TYPE_5G_NSA));
        // Ensure that unknown type is returned.
        collapsedRatTypes.add(TelephonyManager.NETWORK_TYPE_UNKNOWN);
        return toIntArray(collapsedRatTypes);
    }

    @NonNull
    private static int[] toIntArray(@NonNull Collection<Integer> list) {
        final int[] array = new int[list.size()];
        int i = 0;
        for (final Integer item : list) {
            array[i++] = item;
        }
        return array;
    }

    /**
     * Check if matches Wi-Fi network template.
     */
    private boolean matchesWifi(NetworkIdentity ident) {
        switch (ident.mType) {
            case TYPE_WIFI:
                return matchesSubscriberId(ident.mSubscriberId)
                        && matchesWifiNetworkId(ident.mNetworkId);
            default:
                return false;
        }
    }

    /**
     * Check if matches Ethernet network template.
     */
    private boolean matchesEthernet(NetworkIdentity ident) {
        if (ident.mType == TYPE_ETHERNET) {
            return true;
        }
        return false;
    }

    /**
     * Check if matches carrier network. The carrier networks means it includes the subscriberId.
     */
    private boolean matchesCarrier(NetworkIdentity ident) {
        return ident.mSubscriberId != null
                && !ArrayUtils.isEmpty(mMatchSubscriberIds)
                && ArrayUtils.contains(mMatchSubscriberIds, ident.mSubscriberId);
    }

    private boolean matchesMobileWildcard(NetworkIdentity ident) {
        if (ident.mType == TYPE_WIMAX) {
            return true;
        } else {
            return ident.mType == TYPE_MOBILE && matchesCollapsedRatType(ident);
        }
    }

    private boolean matchesWifiWildcard(NetworkIdentity ident) {
        switch (ident.mType) {
            case TYPE_WIFI:
            case TYPE_WIFI_P2P:
                return true;
            default:
                return false;
        }
    }

    /**
     * Check if matches Bluetooth network template.
     */
    private boolean matchesBluetooth(NetworkIdentity ident) {
        if (ident.mType == TYPE_BLUETOOTH) {
            return true;
        }
        return false;
    }

    /**
     * Check if matches Proxy network template.
     */
    private boolean matchesProxy(NetworkIdentity ident) {
        return ident.mType == TYPE_PROXY;
    }

    private static String getMatchRuleName(int matchRule) {
        switch (matchRule) {
            case MATCH_MOBILE:
                return "MOBILE";
            case MATCH_WIFI:
                return "WIFI";
            case MATCH_ETHERNET:
                return "ETHERNET";
            case MATCH_MOBILE_WILDCARD:
                return "MOBILE_WILDCARD";
            case MATCH_WIFI_WILDCARD:
                return "WIFI_WILDCARD";
            case MATCH_BLUETOOTH:
                return "BLUETOOTH";
            case MATCH_PROXY:
                return "PROXY";
            case MATCH_CARRIER:
                return "CARRIER";
            default:
                return "UNKNOWN(" + matchRule + ")";
        }
    }

    private static String getOemManagedNames(int oemManaged) {
        switch (oemManaged) {
            case OEM_MANAGED_ALL:
                return "OEM_MANAGED_ALL";
            case OEM_MANAGED_NO:
                return "OEM_MANAGED_NO";
            case OEM_MANAGED_YES:
                return "OEM_MANAGED_YES";
            default:
                return NetworkIdentity.getOemManagedNames(oemManaged);
        }
    }

    /**
     * Examine the given template and normalize it.
     * We pick the "lowest" merged subscriber as the primary
     * for key purposes, and expand the template to match all other merged
     * subscribers.
     * <p>
     * For example, given an incoming template matching B, and the currently
     * active merge set [A,B], we'd return a new template that primarily matches
     * A, but also matches B.
     * TODO: remove and use {@link #normalize(NetworkTemplate, List)}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static NetworkTemplate normalize(NetworkTemplate template, String[] merged) {
        return normalize(template, Arrays.<String[]>asList(merged));
    }

    /**
     * Examine the given template and normalize it.
     * We pick the "lowest" merged subscriber as the primary
     * for key purposes, and expand the template to match all other merged
     * subscribers.
     *
     * There can be multiple merged subscriberIds for multi-SIM devices.
     *
     * <p>
     * For example, given an incoming template matching B, and the currently
     * active merge set [A,B], we'd return a new template that primarily matches
     * A, but also matches B.
     *
     * @hide
     */
    // TODO: @SystemApi when ready.
    public static NetworkTemplate normalize(NetworkTemplate template, List<String[]> mergedList) {
        // Now there are several types of network which uses SubscriberId to store network
        // information. For instances:
        // The TYPE_WIFI with subscriberId means that it is a merged carrier wifi network.
        // The TYPE_CARRIER means that the network associate to specific carrier network.

        if (template.mSubscriberId == null) return template;

        for (String[] merged : mergedList) {
            if (ArrayUtils.contains(merged, template.mSubscriberId)) {
                // Requested template subscriber is part of the merge group; return
                // a template that matches all merged subscribers.
                return new NetworkTemplate(template.mMatchRule, merged[0], merged,
                        template.mNetworkId);
            }
        }

        return template;
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Creator<NetworkTemplate> CREATOR = new Creator<NetworkTemplate>() {
        @Override
        public NetworkTemplate createFromParcel(Parcel in) {
            return new NetworkTemplate(in);
        }

        @Override
        public NetworkTemplate[] newArray(int size) {
            return new NetworkTemplate[size];
        }
    };
}
