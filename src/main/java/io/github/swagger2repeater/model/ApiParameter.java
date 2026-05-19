package io.github.swagger2repeater.model;

public record ApiParameter(String name, String location, boolean required, String exampleValue) {
}