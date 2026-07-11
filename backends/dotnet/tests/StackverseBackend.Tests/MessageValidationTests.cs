using StackverseBackend.Common;
using StackverseBackend.Messages;

namespace StackverseBackend.Tests;

public class MessageValidationTests
{
    [Fact]
    public void AcceptsAContractShapedMessage()
    {
        var validated = MessageService.Validate(new MessageRequest(
            Key: "ui.nav.my-bookmarks", Language: "en", Text: "My bookmarks", Description: "nav label"));
        Assert.Equal("ui.nav.my-bookmarks", validated.Key);
        Assert.Equal("en", validated.Language);
    }

    [Theory]
    [InlineData("Not.Lower")]
    [InlineData("ends.with.")]
    [InlineData(".starts.with")]
    [InlineData("")]
    public void RejectsMalformedKeys(string key)
    {
        var problem = Assert.Throws<ValidationProblemException>(
            () => MessageService.Validate(new MessageRequest(Key: key, Language: "en", Text: "x")));
        Assert.Contains(problem.Violations, v => v.MessageKey == "validation.message.key.invalid");
    }

    [Theory]
    [InlineData("english")]
    [InlineData("EN")]
    [InlineData("e")]
    public void RejectsNonIsoLanguages(string language)
    {
        var problem = Assert.Throws<ValidationProblemException>(
            () => MessageService.Validate(new MessageRequest(Key: "a.b", Language: language, Text: "x")));
        Assert.Contains(problem.Violations, v => v.MessageKey == "validation.message.language.invalid");
    }

    [Fact]
    public void RejectsEmptyAndOverlongText()
    {
        var empty = Assert.Throws<ValidationProblemException>(
            () => MessageService.Validate(new MessageRequest(Key: "a.b", Language: "en", Text: "")));
        Assert.Contains(empty.Violations, v => v.MessageKey == "validation.message.text.required");

        var overlong = Assert.Throws<ValidationProblemException>(
            () => MessageService.Validate(new MessageRequest(Key: "a.b", Language: "en", Text: new string('x', 2001))));
        Assert.Contains(overlong.Violations, v => v.MessageKey == "validation.message.text.too-long");
    }
}
