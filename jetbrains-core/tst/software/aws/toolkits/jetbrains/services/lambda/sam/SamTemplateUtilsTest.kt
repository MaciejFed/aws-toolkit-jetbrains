// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.sam

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import software.aws.toolkits.core.utils.writeText
import software.aws.toolkits.jetbrains.services.cloudformation.Function
import software.aws.toolkits.jetbrains.services.lambda.sam.SamTemplateUtils.findFunctionsFromTemplate
import software.aws.toolkits.jetbrains.services.lambda.sam.SamTemplateUtils.functionFromElement
import software.aws.toolkits.jetbrains.services.lambda.upload.steps.UploadedEcrCode
import software.aws.toolkits.jetbrains.services.lambda.upload.steps.UploadedS3Code

class SamTemplateUtilsTest {

    @Rule
    @JvmField
    val projectRule = ProjectRule()

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `image uri block is parsed`() {
        val templateFile = tempFolder.newFile().toPath()
        templateFile.writeText(
            """
              Resources:
                Function:
                  Type: AWS::Serverless::Function
                  Properties:
                    PackageType: Image
                    ImageUri: 111122223333.dkr.ecr.us-east-1.amazonaws.com/repo:tag
                    Timeout: 300
                    MemorySize: 128
            """.trimIndent()
        )

        val uploadedCode = SamTemplateUtils.getUploadedCodeUri(templateFile, "Function")
        assertThat(uploadedCode).isInstanceOfSatisfying(UploadedEcrCode::class.java) { code ->
            assertThat(code.imageUri).isEqualTo("111122223333.dkr.ecr.us-east-1.amazonaws.com/repo:tag")
        }
    }

    @Test
    fun `s3 bucket URI is parsed`() {
        val templateFile = tempFolder.newFile().toPath()
        templateFile.writeText(
            """
            Resources:
              Function:
                Type: AWS::Serverless::Function
                Properties:
                  PackageType: Zip
                  Handler: helloworld.App::handleRequest
                  CodeUri: s3://FooBucket/Foo/Key
                  Runtime: java8.al2
                  Timeout: 300
                  MemorySize: 128
            """.trimIndent()
        )

        val uploadedCode = SamTemplateUtils.getUploadedCodeUri(templateFile, "Function")
        assertThat(uploadedCode).isInstanceOfSatisfying(UploadedS3Code::class.java) { code ->
            assertThat(code.bucket).isEqualTo("FooBucket")
            assertThat(code.key).isEqualTo("Foo/Key")
            assertThat(code.version).isNull()
        }
    }

    @Test
    fun `s3 block is parsed`() {
        val templateFile = tempFolder.newFile().toPath()
        templateFile.writeText(
            """
            Resources:
              Function:
                Type: AWS::Serverless::Function
                Properties:
                  PackageType: Zip
                  Handler: helloworld.App::handleRequest
                  CodeUri: 
                    Bucket: FooBucket
                    Key: FooKey
                    Version: FooVersion
                  Runtime: java8.al2
                  Timeout: 300
                  MemorySize: 128
            """.trimIndent()
        )

        val uploadedCode = SamTemplateUtils.getUploadedCodeUri(templateFile, "Function")
        assertThat(uploadedCode).isInstanceOfSatisfying(UploadedS3Code::class.java) { code ->
            assertThat(code.bucket).isEqualTo("FooBucket")
            assertThat(code.key).isEqualTo("FooKey")
            assertThat(code.version).isEqualTo("FooVersion")
        }
    }

    @Test
    fun `no package type is treated as Zip`() {
        val templateFile = tempFolder.newFile().toPath()
        templateFile.writeText(
            """
            Resources:
              Function:
                Type: AWS::Serverless::Function
                Properties:
                  Handler: helloworld.App::handleRequest
                  CodeUri: s3://FooBucket/Foo/Key
                  Runtime: java8.al2
                  Timeout: 300
                  MemorySize: 128
            """.trimIndent()
        )

        val uploadedCode = SamTemplateUtils.getUploadedCodeUri(templateFile, "Function")
        assertThat(uploadedCode).isInstanceOfSatisfying(UploadedS3Code::class.java) { code ->
            assertThat(code.bucket).isEqualTo("FooBucket")
            assertThat(code.key).isEqualTo("Foo/Key")
            assertThat(code.version).isNull()
        }
    }

    @Test
    fun `wrong S3 scheme throws`() {
        val templateFile = tempFolder.newFile().toPath()
        templateFile.writeText(
            """
            Resources:
              Function:
                Type: AWS::Serverless::Function
                Properties:
                  Handler: helloworld.App::handleRequest
                  CodeUri: file://FooBucket/Foo/Key
                  Runtime: java8.al2
                  Timeout: 300
                  MemorySize: 128
            """.trimIndent()
        )

        assertThatThrownBy {
            SamTemplateUtils.getUploadedCodeUri(templateFile, "Function")
        }.hasMessageContaining("does not start with s3://")
    }

    @Test
    fun `wrong S3 URI format throws`() {
        val templateFile = tempFolder.newFile().toPath()
        templateFile.writeText(
            """
            Resources:
              Function:
                Type: AWS::Serverless::Function
                Properties:
                  Handler: helloworld.App::handleRequest
                  CodeUri:  s3://FooBucket
                  Runtime: java8.al2
                  Timeout: 300
                  MemorySize: 128
            """.trimIndent()
        )

        assertThatThrownBy {
            SamTemplateUtils.getUploadedCodeUri(templateFile, "Function")
        }.hasMessageContaining("does not follow the format s3://<bucket>/<key>")
    }

    @Test
    fun `missing logical id throws`() {
        val templateFile = tempFolder.newFile().toPath()
        templateFile.writeText(
            """
            Resources:
              Function:
                Type: AWS::Serverless::Function
                Properties:
                  Handler: helloworld.App::handleRequest
                  CodeUri:  file://FooBucket/Foo/Key
                  Runtime: java8.al2
                  Timeout: 300
                  MemorySize: 128
            """.trimIndent()
        )

        assertThatThrownBy {
            SamTemplateUtils.getUploadedCodeUri(templateFile, "MissingFunction")
        }.hasMessageContaining("No resource with the logical ID MissingFunction")
    }

    @Test
    fun `can pull functions from a sam template`() {
        val file = yamlFile().virtualFile

        runInEdtAndWait {
            val functions = findFunctionsFromTemplate(projectRule.project, file)
            assertThat(functions).hasSize(2)
            assertFunction(functions[0], "MySamFunction", "hello.zip", "helloworld.App::handleRequest", "java8")
            assertFunction(functions[1], "MyLambdaFunction", "foo.zip", "foobar.App::handleRequest", "java8")
        }
    }

    @Test
    fun `can pull function from a sam template with global`() {
        val file = yamlFileWithGlobal().virtualFile
        runInEdtAndWait {
            val functions = findFunctionsFromTemplate(projectRule.project, file)
            assertThat(functions).hasSize(2)
            assertFunction(functions[0], "MySamFunction", "hello.zip", "helloworld.App::handleRequest", "java8")
            assertFunction(functions[1], "MyLambdaFunction", "foo.zip", "foobar.App::handleRequest", "java8")
        }
    }

    @Test
    fun `can convert a psi element function`() {
        val file = yamlFile()

        val function = runInEdtAndGet {
            val functionElement =
                file.findByLocation("Resources.MySamFunction") ?: throw RuntimeException("Can't find MySamFunction")
            functionFromElement(functionElement)
        } ?: throw AssertionError("Function not found")

        assertFunction(function, "MySamFunction", "hello.zip", "helloworld.App::handleRequest", "java8")
    }

    private fun yamlFile(): YAMLFile = runInEdtAndGet {
        PsiFileFactory.getInstance(projectRule.project).createFileFromText(
            YAMLLanguage.INSTANCE,
            """
Resources:
    MySamFunction:
        Type: AWS::Serverless::Function
        Properties:
            CodeUri: hello.zip
            Handler: helloworld.App::handleRequest
            Runtime: java8
    MyLambdaFunction:
        Type: AWS::Lambda::Function
        Properties:
            Code: foo.zip
            Handler: foobar.App::handleRequest
            Runtime: java8
            """.trimIndent()
        ) as YAMLFile
    }

    private fun yamlFileWithGlobal(): YAMLFile = runInEdtAndGet {
        PsiFileFactory.getInstance(projectRule.project).createFileFromText(
            YAMLLanguage.INSTANCE,
            """
Globals:
    Function:
        Runtime: java8
        Timeout: 180
Resources:
    MySamFunction:
        Type: AWS::Serverless::Function
        Properties:
            CodeUri: hello.zip
            Handler: helloworld.App::handleRequest
    MyLambdaFunction:
        Type: AWS::Lambda::Function
        Properties:
            Code: foo.zip
            Handler: foobar.App::handleRequest
            Runtime: java8
            """.trimIndent()
        ) as YAMLFile
    }

    private fun assertFunction(
        function: Function,
        logicalName: String,
        codeLocation: String,
        handler: String,
        runtime: String
    ) {
        runInEdtAndWait {
            assertThat(function.logicalName).isEqualTo(logicalName)
            assertThat(function.codeLocation()).isEqualTo(codeLocation)
            assertThat(function.handler()).isEqualTo(handler)
            assertThat(function.runtime()).isEqualTo(runtime)
        }
    }
}

fun YAMLFile.findByLocation(location: String): YAMLKeyValue? = (documents.firstOrNull()?.topLevelValue as? YAMLMapping)?.findByLocation(location)

fun YAMLMapping.findByLocation(location: String): YAMLKeyValue? {
    val parts = location.split('.')
    val head = parts.first()
    val tail = parts.takeLast(parts.size - 1)
    return when (tail.isEmpty()) {
        true -> getKeyValueByKey(head)
        false -> (getKeyValueByKey(head)?.value as? YAMLMapping)?.findByLocation(tail.joinToString("."))
    }
}
