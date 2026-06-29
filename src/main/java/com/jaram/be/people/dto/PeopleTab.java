package com.jaram.be.people.dto;

import java.util.List;

// Matches OpenAPI schema PeopleTab.
public record PeopleTab(String desc, String empty, List<PeopleGroup> groups) { }
