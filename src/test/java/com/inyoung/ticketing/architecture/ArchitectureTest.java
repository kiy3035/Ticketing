package com.inyoung.ticketing.architecture;

import com.inyoung.ticketing.TicketingApplication;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit: 패키지 의존 규칙을 테스트로 고정한다.
 * <p>
 * CI 에서 깨지면 "레이어를 어긴 변경"이므로 리뷰 포인트로 삼기 좋다.
 * </p>
 */
@AnalyzeClasses(
	packagesOf = TicketingApplication.class,
	importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

	/** 컨트롤러는 서비스만 알고, JPA Repository 에 직접 의존하지 않는다. */
	@ArchTest
	static final ArchRule controllersDoNotTouchRepositories = noClasses()
		.that().resideInAPackage("..controller..")
		.should().dependOnClassesThat().resideInAPackage("..repository..");

	/** 순수 도메인 엔티티/값은 Spring 프레임워크 타입에 의존하지 않는다(jakarta.* 는 허용). */
	@ArchTest
	static final ArchRule domainIndependentOfSpring = noClasses()
		.that().resideInAPackage("..domain..")
		.should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

	/** 서비스가 컨트롤러를 참조하면 순환·레이어 혼란이 생기기 쉬워 금지한다. */
	@ArchTest
	static final ArchRule servicesDoNotDependOnControllers = noClasses()
		.that().resideInAPackage("..service..")
		.should().dependOnClassesThat().resideInAPackage("..controller..");
}
