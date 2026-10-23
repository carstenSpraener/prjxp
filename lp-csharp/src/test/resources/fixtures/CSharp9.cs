namespace Modern.CSharp9
{
    // Positional record (C# 9)
    public record Point(int X, int Y);

    public class Person
    {
        public string Name { get; init; }
        public int Age { get; init; }

        public Person WithName(string name)
        {
            // With expression (C# 9)
            return this with { Name = name };
        }
    }
}
