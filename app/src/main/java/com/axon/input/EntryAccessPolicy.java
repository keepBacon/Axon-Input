package com.axon.input;

/** 入口密码由 GitHub cloud-control/security.json 远程策略校验。 */
final class EntryAccessPolicy {
    private EntryAccessPolicy() {}

    static boolean matches(String value) {
        return GitHubCloudPolicy.matchesEntryPassword(value);
    }
}
