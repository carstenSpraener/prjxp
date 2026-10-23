namespace Modern.CSharp11
{
    public class Attr<T> : System.Attribute
    {
    }

    [Attr<string>]
    public class Config
    {
        public required string Host { get; init; }
        public int Port { get; init; }

        public string Template()
        {
            #nullable enable
            // Raw string literal (C# 11)
            return """
                   <config>
                       <host>localhost</host>
                       <port>8080</port>
                   </config>
                   """;
        }
    }
}
