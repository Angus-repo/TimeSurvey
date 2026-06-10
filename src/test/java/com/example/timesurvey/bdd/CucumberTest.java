package com.example.timesurvey.bdd;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/** Cucumber BDD 測試進入點：mvn test 會執行 src/test/resources/features 下所有 .feature */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
public class CucumberTest {
}
