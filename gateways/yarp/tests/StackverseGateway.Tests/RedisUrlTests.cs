namespace StackverseGateway.Tests;

public sealed class RedisUrlTests
{
    [Theory]
    [InlineData("redis://localhost:6379", "localhost:6379")]
    [InlineData("redis://redis", "redis:6379")]
    [InlineData("redis://:s3cret@redis:6380/2", "redis:6380,password=s3cret,defaultDatabase=2")]
    [InlineData("redis://user:s3cret@redis:6380", "redis:6380,user=user,password=s3cret")]
    [InlineData("redis://s3cret@redis:6380", "redis:6380,password=s3cret")]
    [InlineData("rediss://redis:6380", "redis:6380,ssl=true")]
    [InlineData("localhost:6379,abortConnect=false", "localhost:6379,abortConnect=false")]
    public void Redis_urls_translate_to_stackexchange_configuration_strings(string url, string expected)
    {
        Assert.Equal(expected, GatewayOptions.ParseRedisUrl(url));
    }
}
