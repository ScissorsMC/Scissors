package io.papermc.paper.item;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

// Isolate Paper's static packet sanitizer from suites that initialize configuration without a server.
@Suite
@SelectClasses(CachedItemSerializationTest.class)
public class CachedItemSerializationTestSuite {
}
