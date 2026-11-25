using System.Collections.Generic;

namespace Modern.CSharp12
{
    public record Dep(string Name);

    // Primary constructor (C# 12)
    public class Service(Dep dep)
    {
        public string Describe() => "Service for " + dep.Name;
    }

    public static class Collections
    {
        public static List<int> Make()
        {
            #region values
            // Collection expression (C# 12)
            return new List<int> { 1, 2, 3 };
            #endregion
        }

        public static T Default<T>() where T : new()
        {
            return new T();
        }
    }
}
