package io.papermc.paper.scissors;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

// Isolate Paper's static packet sanitizer from suites that initialize configuration without a server.
@Suite
@SelectClasses(UnsafeEquipmentTest.class)
public class UnsafeEquipmentTestSuite {
}
