package com.jaram.be.people.dto;

import java.util.List;

// Matches OpenAPI schema PeopleGroup. heading is the department name (null for contrib/grad).
public record PeopleGroup(String heading, List<PersonMember> members) { }
