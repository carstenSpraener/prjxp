namespace Acme
{
    public record Point(int X, int Y)
    {
        public int Sum()
        {
            return X + Y;
        }
    }
}
