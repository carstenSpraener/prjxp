using System;
using System.IO;

namespace Modern.CSharp8
{
    public interface IShape
    {
        double Area { get; }

        // Default interface member (C# 8)
        double Perimeter() => 2.0 * Area;
    }

    public class Circle : IShape
    {
        public double Area { get; }

        public Circle(double area)
        {
            Area = area;
        }
    }

    public static class CSharp8Features
    {
        public static int Classify(int n)
        {
            // Switch expression (C# 8)
            string label = n switch
            {
                0 => "zero",
                1 => "one",
                _ => "many"
            };
            return label.Length;
        }

        public static int Slice(int[] arr)
        {
            // Range (C# 8)
            int[] slice = arr[1..3];
            return slice.Length;
        }

        public static void Log()
        {
            // Using declaration (C# 8)
            using var writer = new StreamWriter("log.txt");
            writer.WriteLine("done");
        }
    }
}
