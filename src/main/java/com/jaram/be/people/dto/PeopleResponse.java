package com.jaram.be.people.dto;

// Matches OpenAPI schema PeopleResponse. Three tabs keyed by member category.
public record PeopleResponse(PeopleTab exec, PeopleTab contrib, PeopleTab grad) { }
