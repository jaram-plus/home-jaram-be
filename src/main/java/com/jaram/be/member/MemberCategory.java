package com.jaram.be.member;

// enum name == JSON wire value. regular is internal-only (default base
// membership, not referenced by the contract); exec/contrib/grad are the
// awardable people-tab categories and may be held simultaneously.
public enum MemberCategory { regular, exec, contrib, grad }
