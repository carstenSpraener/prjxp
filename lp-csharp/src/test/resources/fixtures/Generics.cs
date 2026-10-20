namespace Acme
{
    public interface IRepository<T> where T : class
    {
        T Find(int id);
    }

    public class Repository<T> where T : class
    {
        public T Find(int id)
        {
            return default;
        }
    }
}
