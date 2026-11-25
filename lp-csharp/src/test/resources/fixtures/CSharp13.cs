namespace Modern.CSharp13
{
    public class Widget
    {
        private string _name = "widget";

        // Field keyword (C# 13)
        public string Name
        {
            get => field;
            set => field = value;
        }

        // Generic type in a params list (C# 13)
        public static T First<T>(params T[] items)
        {
            if (items.Length == 0)
            {
                return default;
            }
            return items[0];
        }
    }
}
